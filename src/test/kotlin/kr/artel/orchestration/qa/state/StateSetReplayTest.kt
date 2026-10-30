package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * 실제 QA 런의 `pulse` 열을 처음부터 끝까지 적용한다(ARTEL-1296).
 *
 * fixture 는 로컬 런 155(try 205, WordVenture 1.4.0)의 `SDK_TO_ORCHE` `pulse` 853개 원문이다. Title → Story →
 * Map → TurnBattle 로 씬이 넷 바뀌고 whole 이 넷 있다. `gone`·`gaps`·`unread` 는 이 런에 없어서
 * [StateSetContractTest] 가 합성 fixture 로 맡는다.
 *
 * 기대값은 reducer 를 거치지 않고 fixture 를 직접 읽어 만든다. 마지막 whole 부터 끝까지 실린 객체와 값이 곧
 * 지금 상태여야 한다.
 */
class StateSetReplayTest {

    private val objectMapper = jacksonObjectMapper()
    private val rows: List<JsonNode> = javaClass.getResourceAsStream(FIXTURE)!!.use { stream ->
        GZIPInputStream(stream).bufferedReader().readLines().map(objectMapper::readTree)
    }

    private fun replay(): StateSet {
        val state = StateSet(gameInstanceId = 1)
        rows.forEach { row ->
            val outcome = state.observe("connection-1", objectMapper.treeToValue(row, StatePulse::class.java), Instant.EPOCH)
            assertThat(outcome).isEqualTo(ObserveOutcome.APPLIED)
        }
        return state
    }

    @Test
    fun `853 개를 모두 적용하고 마지막 씬에 동기화돼 있다`() {
        val snapshot = replay().snapshot(ObservationSpec(maxEntities = Int.MAX_VALUE))

        assertThat(rows).hasSize(853)
        assertThat(snapshot.stateVersion).isEqualTo(853)
        assertThat(snapshot.sync).isEqualTo(SyncState.SYNCED)
        assertThat(snapshot.scene).isEqualTo("TurnBattleScene")
        assertThat(snapshot.sceneScope).isEqualTo("scene:TurnBattleScene#1")
        assertThat(snapshot.reading).isEqualTo(rows.last()["reading"].asLong())
        assertThat(snapshot.omittedEntities).isZero()
    }

    @Test
    fun `떠난 씬의 객체는 남지 않고 지금 객체 집합이 fixture 와 같다`() {
        val snapshot = replay().snapshot(ObservationSpec(maxEntities = Int.MAX_VALUE))

        val expectedIds = sinceLastWhole().flatMap { objectsOf(it) }.map { "s1:${it.first["id"].asLong()}" }.toSet()
        assertThat(snapshot.entities.map { it.entityId }).containsExactlyInAnyOrderElementsOf(expectedIds)
        assertThat(snapshot.entities.map { it.scope }.toSet())
            .allSatisfy { scope -> assertThat(scope).isIn("persistent", "scene:TurnBattleScene#1") }
        assertThat(snapshot.entities).allSatisfy { assertThat(it.status).isEqualTo(ObservationStatus.KNOWN) }
    }

    @Test
    fun `DontDestroyOnLoad 객체는 씬이 바뀌어도 같은 entityId 로 이어진다`() {
        val wholes = rows.filter { it["whole"].asBoolean() }
        val persistentIds = { whole: JsonNode ->
            objectsOf(whole).filter { it.first["scene"]?.asText() == StateSet.PERSISTENT_SCENE }.map { it.first["id"].asLong() }.toSet()
        }
        val survivors = persistentIds(wholes.first()) intersect persistentIds(wholes.last())
        assertThat(survivors).isNotEmpty()

        val snapshot = replay().snapshot(ObservationSpec(scopes = listOf("persistent"), maxEntities = Int.MAX_VALUE))
        val byId = snapshot.entities.associateBy { it.sdkId }
        survivors.forEach { id ->
            assertThat(byId.getValue(id).entityId).isEqualTo("s1:$id")
            assertThat(byId.getValue(id).scope).isEqualTo("persistent")
        }
    }

    @Test
    fun `켜짐 꺼짐과 멤버 값이 마지막으로 실린 것과 같다`() {
        val snapshot = replay().snapshot(ObservationSpec(maxEntities = Int.MAX_VALUE))

        val expectedActive = HashMap<String, Boolean>()
        val expectedMembers = HashMap<String, MutableMap<String, JsonNode>>()
        for (row in sinceLastWhole()) {
            for ((pulseObject, isActive) in objectsOf(row)) {
                val entityId = "s1:${pulseObject["id"].asLong()}"
                expectedActive[entityId] = isActive
                val members = expectedMembers.getOrPut(entityId) { HashMap() }
                for (component in pulseObject["by"] ?: emptyList()) {
                    for (member in component["m"]) {
                        val memberId = "${component["on"].asText()}::${member["member"].asText()}#${member["among"]?.asInt() ?: 0}"
                        members[memberId] = member["value"] ?: member["count"]?.let { IntNode(it.asInt()) } ?: NullNode.instance
                    }
                }
            }
        }

        assertThat(snapshot.entities).allSatisfy { entity ->
            assertThat(entity.active).isEqualTo(expectedActive.getValue(entity.entityId))
            val actual = entity.members.mapValues { (_, observation) -> observation.value }
            assertThat(actual).isEqualTo(expectedMembers.getValue(entity.entityId))
        }
    }

    @Test
    fun `static 은 씬이 바뀌어도 지워지지 않고 마지막 whole 이후 값이 known 이다`() {
        val snapshot = replay().snapshot(ObservationSpec(scopes = listOf("runtime")))

        val expected = HashMap<String, JsonNode>()
        for (row in sinceLastWhole()) {
            for (entry in row["statics"]) {
                expected["${entry["declaring"].asText()}::${entry["member"].asText()}"] =
                    entry["value"] ?: entry["count"]?.let { IntNode(it.asInt()) } ?: NullNode.instance
            }
        }
        val known = snapshot.statics.filter { it.observation.status == ObservationStatus.KNOWN }
        assertThat(known.associate { it.memberId to it.observation.value }).isEqualTo(expected)

        val everSeen = rows.flatMap { row -> row["statics"].map { "${it["declaring"].asText()}::${it["member"].asText()}" } }.toSet()
        assertThat(snapshot.statics.map { it.memberId }).containsExactlyInAnyOrderElementsOf(everSeen)
    }

    private fun sinceLastWhole(): List<JsonNode> = rows.drop(rows.indexOfLast { it["whole"].asBoolean() })

    private fun objectsOf(row: JsonNode): List<Pair<JsonNode, Boolean>> =
        row["active"].map { it to true } + row["deactive"].map { it to false }

    companion object {
        const val FIXTURE = "/qa/state/wordventure-try205-pulses.jsonl.gz"
    }
}
