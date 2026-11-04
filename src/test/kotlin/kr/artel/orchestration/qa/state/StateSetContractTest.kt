package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `STATE_SET` 계약 v0.1 의 규칙 하나마다 합성 `pulse` 로 고정한다(ARTEL-1296).
 *
 * 규칙의 출처는 `artel/docs/architecture/2026-10-29-state-set-contract.md` 의 `전체 관측과 delta`·`순서와 복구`·
 * `값과 관측 상태` 절이다. 실제 런으로 재현되는 규칙은 [StateSetReplayTest] 가 따로 본다.
 */
class StateSetContractTest {

    private val objectMapper = jacksonObjectMapper()
    private val state = StateSet(gameInstanceId = 7)

    private fun send(json: String, connection: String = "connection-1"): ObserveOutcome =
        state.observe(connection, objectMapper.readValue(json, StatePulse::class.java), Instant.EPOCH)

    private fun snapshot(spec: ObservationSpec = ObservationSpec()) = state.snapshot(spec)

    private fun entity(sdkId: Long, spec: ObservationSpec = ObservationSpec()) =
        snapshot(spec).entities.single { it.sdkId == sdkId }

    private fun player(hp: String, extra: String = "") =
        """{"id":12,"path":"Player","selector":"Player[0]","by":[{"on":"Battle.Player","m":[{"member":"hp","value":$hp}$extra]}]}"""

    private fun whole(scene: String, active: String = "", statics: String = "", gaps: String? = null, reading: Int = 1) =
        """{"reading":$reading,"frame":${reading * 10},"scene":"$scene","whole":true,"active":[$active],"statics":[$statics]""" +
            (gaps?.let { ""","gaps":$it""" } ?: "") + "}"

    private fun delta(scene: String, body: String, reading: Int = 2) =
        """{"reading":$reading,"frame":${reading * 10},"scene":"$scene","whole":false,$body}"""

    @Test
    fun `whole 이 오기 전의 델타는 적용하지 않는다`() {
        assertThat(send(delta("Battle", """"active":[${player("80")}]"""))).isEqualTo(ObserveOutcome.IGNORED_AWAITING_WHOLE)

        val before = snapshot()
        assertThat(before.sync).isEqualTo(SyncState.AWAITING_WHOLE)
        assertThat(before.entities).isEmpty()
        assertThat(before.ignoredDeltas).isEqualTo(1)
        assertThat(before.stateVersion).isZero()

        send(whole("Battle", player("80")))
        assertThat(snapshot().sync).isEqualTo(SyncState.SYNCED)
        assertThat(snapshot().stateVersion).isEqualTo(1)
    }

    @Test
    fun `생략은 유지이고 false 0 빈값 null 은 실제 값으로 바뀐다`() {
        send(whole("Battle", player("80", """,{"member":"alive","value":true},{"member":"name","value":"slime"},{"member":"target","value":{"path":"Enemy"}}""")))
        send(delta("Battle", """"active":[{"id":12,"by":[{"on":"Battle.Player","m":[{"member":"hp","value":0},{"member":"alive","value":false}]}]}]"""))
        send(delta("Battle", """"active":[{"id":12,"by":[{"on":"Battle.Player","m":[{"member":"name","value":""},{"member":"target","value":null}]}]}]""", reading = 3))

        val members = entity(12).members
        assertThat(members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(0))
        assertThat(members.getValue("Battle.Player::alive#0").value).isEqualTo(BooleanNode.FALSE)
        assertThat(members.getValue("Battle.Player::name#0").value).isEqualTo(TextNode(""))
        assertThat(members.getValue("Battle.Player::target#0").value).isEqualTo(NullNode.instance)
        assertThat(members.values).allSatisfy { assertThat(it.status).isEqualTo(ObservationStatus.KNOWN) }
        // 델타가 path 를 안 실어도 whole 이 준 path 가 남는다.
        assertThat(entity(12).path).isEqualTo("Player")
        assertThat(members.getValue("Battle.Player::hp#0").sourceReading).isEqualTo(2)
    }

    @Test
    fun `못 읽은 값은 unread 와 이유로, 컬렉션은 개수로 남는다`() {
        send(whole("Battle", """{"id":12,"path":"Player","by":[{"on":"Core.InteractionLock","m":[{"member":"IsLocked","unread":"property runs code"},{"member":"cards","count":3}]}]}"""))

        val members = entity(12).members
        assertThat(members.getValue("Core.InteractionLock::IsLocked#0").status).isEqualTo(ObservationStatus.UNREAD)
        assertThat(members.getValue("Core.InteractionLock::IsLocked#0").reason).isEqualTo("property runs code")
        assertThat(members.getValue("Core.InteractionLock::cards#0").count).isEqualTo(3)
        assertThat(members.getValue("Core.InteractionLock::cards#0").status).isEqualTo(ObservationStatus.KNOWN)
    }

    @Test
    fun `꺼진 객체는 지우지 않고 active false 로 두고 gone 만 지운다`() {
        send(whole("Battle", """${player("80")},{"id":13,"path":"Card","selector":"Hand[0]/Card[2]"}"""))
        send(delta("Battle", """"deactive":[{"id":12,"path":"Player","selector":"Player[0]"}],"gone":["Battle/Hand[0]/Card[2]"]"""))

        val entities = snapshot().entities
        assertThat(entities.map { it.sdkId }).containsExactly(12)
        assertThat(entities.single().active).isFalse()
        assertThat(entities.single().members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(80))
        assertThat(snapshot(ObservationSpec(includeInactive = false)).entities).isEmpty()
    }

    @Test
    fun `fragment 는 실려 올 때마다 통째로 바뀐다`() {
        send(whole("Battle", """{"id":20,"path":"Canvas/Ok","ui":{"interactable":true,"sprite":"Wood"},"text":"OK"}"""))
        send(delta("Battle", """"active":[{"id":20,"ui":{"interactable":false}}]"""))

        val fragments = entity(20).fragments
        assertThat(fragments.getValue("ui").value).isEqualTo(objectMapper.readTree("""{"interactable":false}"""))
        assertThat(fragments.getValue("text").value).isEqualTo(TextNode("OK"))
    }

    @Test
    fun `완전한 whole 은 다시 안 실린 객체와 떠난 씬을 지우고 static 과 DontDestroyOnLoad 는 남긴다`() {
        send(whole("Map", """{"id":1,"path":"Stage"},{"id":2,"scene":"DontDestroyOnLoad","path":"Audio"}""",
            statics = """{"declaring":"Save","member":"stage","value":3}"""))
        send(whole("Battle", """{"id":3,"path":"Player"},{"id":2,"scene":"DontDestroyOnLoad","path":"Audio"}""", reading = 2))

        val snapshot = snapshot()
        assertThat(snapshot.entities.map { it.sdkId to it.scope }).containsExactlyInAnyOrder(2L to "persistent", 3L to "scene:Battle#1")
        val stage = snapshot.statics.single { it.memberId == "Save::stage" }
        assertThat(stage.observation.status).isEqualTo(ObservationStatus.STALE)
        assertThat(stage.observation.value).isEqualTo(IntNode(3))
    }

    @Test
    fun `잘린 whole 은 안 실린 객체를 지우지 않고 stale 로 둔다`() {
        send(whole("Battle", """{"id":1,"path":"A"},{"id":2,"path":"B"}"""))
        send(whole("Battle", """{"id":1,"path":"A"}""", gaps = """["object cap 400"]""", reading = 2))

        val byId = snapshot().entities.associateBy { it.sdkId }
        assertThat(byId.getValue(1).status).isEqualTo(ObservationStatus.KNOWN)
        assertThat(byId.getValue(2).status).isEqualTo(ObservationStatus.STALE)
        assertThat(snapshot().coverage!!.gaps).containsExactly("object cap 400")
        assertThat(snapshot(ObservationSpec(includeStale = false)).entities.map { it.sdkId }).containsExactly(1L)
    }

    @Test
    fun `새 연결은 새 stream 이고 whole 전까지 옛 값은 stale 이며 델타를 받지 않는다`() {
        send(whole("Battle", player("80")))
        assertThat(send(delta("Battle", """"active":[${player("70")}]"""), connection = "connection-2"))
            .isEqualTo(ObserveOutcome.IGNORED_AWAITING_WHOLE)

        val resyncing = snapshot()
        assertThat(resyncing.sync).isEqualTo(SyncState.AWAITING_WHOLE)
        assertThat(resyncing.streamId).isEqualTo("s2")
        assertThat(resyncing.entities.single().status).isEqualTo(ObservationStatus.STALE)
        assertThat(resyncing.entities.single().members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(80))

        send(whole("Battle", player("70")), connection = "connection-2")
        val synced = snapshot()
        assertThat(synced.entities.map { it.entityId }).containsExactly("s2:12")
        assertThat(synced.entities.single().members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(70))
    }

    @Test
    fun `같은 씬에 다시 들어오면 세대가 오른다`() {
        send(whole("Map", """{"id":1,"path":"Stage"}"""))
        send(whole("Battle", """{"id":2,"path":"Player"}""", reading = 2))
        send(whole("Map", """{"id":3,"path":"Stage"}""", reading = 3))

        assertThat(snapshot().sceneScope).isEqualTo("scene:Map#2")
        assertThat(snapshot().entities.single().scope).isEqualTo("scene:Map#2")
    }

    @Test
    fun `ObservationSpec 은 멤버와 static 을 이름으로 고르고 없는 이름은 unobserved 로 답한다`() {
        send(whole("Battle", player("80", """,{"member":"mp","value":5}"""),
            statics = """{"declaring":"Save","member":"stage","value":3},{"declaring":"Save","member":"gold","value":9}"""))

        val spec = ObservationSpec(
            members = listOf("Battle.Player::hp", "Battle.Enemy::hp"),
            statics = listOf("Save::stage", "Core.InteractionLock::IsLocked"),
        )
        val snapshot = snapshot(spec)
        assertThat(snapshot.entities.single().members.keys).containsExactly("Battle.Player::hp#0")
        assertThat(snapshot.statics.map { it.memberId }).containsExactly("Save::stage")
        assertThat(snapshot.unobserved).containsExactly("Battle.Enemy::hp", "Core.InteractionLock::IsLocked")
    }

    @Test
    fun `ObservationSpec 은 scope 와 path 로 거르고 상한을 넘은 수를 알린다`() {
        send(whole("Battle", """{"id":1,"path":"Canvas/A"},{"id":2,"path":"Canvas/B"},{"id":3,"path":"World/C"},{"id":4,"scene":"DontDestroyOnLoad","path":"Audio"}"""))

        assertThat(snapshot(ObservationSpec(scopes = listOf("scene"))).entities.map { it.sdkId }).containsExactly(1L, 2L, 3L)
        assertThat(snapshot(ObservationSpec(scopes = listOf("persistent"))).entities.map { it.sdkId }).containsExactly(4L)
        assertThat(snapshot(ObservationSpec(scopes = listOf("scene"))).statics).isEmpty()

        val limited = snapshot(ObservationSpec(paths = listOf("Canvas/"), maxEntities = 1))
        assertThat(limited.entities.map { it.sdkId }).containsExactly(1L)
        assertThat(limited.omittedEntities).isEqualTo(1)
    }

    // ── SDK 세대·순번(ARTEL-1300) ─────────────────────────────────────────────

    private fun stamped(json: String, runtime: String = "run-a", stream: String = "st-1", seq: Long) =
        "{\"runtime\":\"$runtime\",\"stream\":\"$stream\",\"seq\":$seq," + json.removePrefix("{")

    @Test
    fun `seq 가 이어지면 적용하고 이미 받은 번호는 버린다`() {
        send(stamped(whole("Battle", player("80")), seq = 1))
        assertThat(send(stamped(delta("Battle", """"active":[${player("70")}]"""), seq = 2))).isEqualTo(ObserveOutcome.APPLIED)
        assertThat(send(stamped(delta("Battle", """"active":[${player("60")}]"""), seq = 2))).isEqualTo(ObserveOutcome.IGNORED_DUPLICATE)

        assertThat(entity(12).members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(70))
        assertThat(snapshot().duplicates).isEqualTo(1)
    }

    @Test
    fun `seq 에 빈자리가 있으면 whole 을 기다리고 한 번만 알린다`() {
        send(stamped(whole("Battle", player("80")), seq = 1))
        assertThat(send(stamped(delta("Battle", """"active":[${player("70")}]"""), seq = 3))).isEqualTo(ObserveOutcome.GAP_DETECTED)
        assertThat(send(stamped(delta("Battle", """"active":[${player("60")}]"""), seq = 4))).isEqualTo(ObserveOutcome.IGNORED_AWAITING_WHOLE)

        val waiting = snapshot()
        assertThat(waiting.sync).isEqualTo(SyncState.AWAITING_WHOLE)
        assertThat(waiting.seqGaps).isEqualTo(1)
        assertThat(entity(12).members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(80))

        assertThat(send(stamped(whole("Battle", player("50")), seq = 5))).isEqualTo(ObserveOutcome.APPLIED)
        assertThat(snapshot().sync).isEqualTo(SyncState.SYNCED)
        assertThat(entity(12).members.getValue("Battle.Player::hp#0").value).isEqualTo(IntNode(50))
    }

    @Test
    fun `같은 연결에서도 SDK stream 이 바뀌면 새 stream 이다`() {
        send(stamped(whole("Battle", player("80")), stream = "st-1", seq = 1))
        assertThat(send(stamped(delta("Battle", """"active":[${player("70")}]"""), stream = "st-2", seq = 1)))
            .isEqualTo(ObserveOutcome.IGNORED_AWAITING_WHOLE)
        assertThat(snapshot().streamId).isEqualTo("s2")

        send(stamped(whole("Battle", player("70")), stream = "st-2", seq = 2))
        assertThat(snapshot().entities.map { it.entityId }).containsExactly("s2:12")
    }

    @Test
    fun `runtime 이 바뀌면 옛 상태를 stale 로도 남기지 않는다`() {
        send(stamped(whole("Battle", player("80"), statics = """{"declaring":"Save","member":"stage","value":3}"""), seq = 1))
        send(stamped(delta("Battle", """"active":[${player("70")}]"""), runtime = "run-b", stream = "st-9", seq = 1))

        val restarted = snapshot()
        assertThat(restarted.entities).isEmpty()
        assertThat(restarted.statics).isEmpty()
        assertThat(restarted.runtime).isEqualTo("run-b")
        assertThat(restarted.runtimeChanges).isEqualTo(1)
        assertThat(restarted.sync).isEqualTo(SyncState.AWAITING_WHOLE)
    }

    @Test
    fun `같은 이름의 씬을 다시 로드하면 handle 로 세대가 오른다`() {
        val withScenes = { handle: Int, body: String -> body.replaceFirst("\"scene\":", "\"scenes\":[{\"name\":\"Battle\",\"handle\":$handle}],\"scene\":") }
        send(stamped(withScenes(101, whole("Battle", """{"id":1,"path":"Player"}""")), seq = 1))
        assertThat(snapshot().sceneScope).isEqualTo("scene:Battle#1")

        send(stamped(withScenes(202, whole("Battle", """{"id":2,"path":"Player"}""", reading = 2)), seq = 2))
        val reloaded = snapshot()
        assertThat(reloaded.sceneScope).isEqualTo("scene:Battle#2")
        assertThat(reloaded.entities.map { it.sdkId to it.scope }).containsExactly(2L to "scene:Battle#2")
    }
}
