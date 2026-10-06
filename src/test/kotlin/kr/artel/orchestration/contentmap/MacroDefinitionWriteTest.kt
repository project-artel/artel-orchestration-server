package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.macro.MacroWriteFrames
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "macro-write-session"
private const val BATTLE = "TurnBattleScene"
private const val MACRO = "attack_with_combined_card"

/**
 * 등록된 macro 를 적고 다시 읽는 경로(ARTEL-919 · ARTEL-921).
 *
 * 계약은 `contentmap/macro/MacroWriteFrames.kt` 가 진다. 이 파일이 지키는 것은 넷이다.
 *
 * 1. **ARTEL-921 이 요구한 세 경로** — 정상 쓰기 · 정상 읽기 · 거부
 * 2. **등록 갱신이 제자리에서 일어난다** — macro 의 `id` 가 그대로이고 `screen_macro` 행이 남는다.
 *    지우고 새로 넣으면 `ON DELETE CASCADE` 로 관계가 사라지고, 그것이 이 표 설계의 핵이다
 * 3. **`remedy` 없는 `require` 가 두 겹에서 막힌다** — 서비스가 사유를 돌려주고, DB CHECK 가
 *    서비스를 지나지 않는 쓰기까지 막는다. 후자는 repository 를 직접 찔러 본다
 * 4. **라우터 가드 둘이 macro 프레임에도 걸린다** — 비 UUID `messageId` 와 끝난 `qaTryId` 는
 *    outbound 프레임 없이 떨어진다
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MacroDefinitionWriteTest {

    class RecordingAgentPort : QaAgentPort {
        val sent: MutableList<QaAgentEnvelope> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession(SESSION_ID)

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
            sent += envelope
        }

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        @Primary
        fun recordingAgentPort(): QaAgentPort = RecordingAgentPort()
    }

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var macros: MacroRepository
    @Autowired private lateinit var screenMacros: ScreenMacroRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var qaLogs: QaLogRepository
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var identities: OAuthIdentityRepository
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agentPort: QaAgentPort

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort

    /**
     * 이 스위트가 만든 것을 스스로 치운다. `qa_run` 을 남기면 뒤에 도는 다른 스위트의 전역 삭제가
     * FK 에 막힌다(ARTEL-661).
     *
     * `screen_macro` 를 `macro` · `screen` 보다 먼저 비운다. 양쪽을 `ON DELETE CASCADE` 로 물고
     * 있어 순서를 바꿔도 결과는 같지만, 지우는 순서가 곧 의존 방향이라 그대로 적어 둔다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        recorder.sent.clear()
        screenMacros.deleteAll()
        macros.deleteAll()
        screens.deleteAll()
        scenes.deleteAll()
        contentMaps.deleteAll()
        qaLogs.deleteAll()
        qaTries.deleteAll()
        qaRuns.deleteAll()
        testRuns.deleteAll()
        testScenarios.deleteAll()
        gameInstances.deleteAll()
        gameBuilds.deleteAll()
        projects.deleteAll()
        identities.deleteAll()
        appUsers.deleteAll()
    }

    @Test
    fun `등록한 macro 가 지도에 서고 id 가 문자열로 돌아온다`(): Unit = runBlocking {
        val world = newWorld()

        register(world, name = MACRO, screens = listOf(world.screenA))

        val row = macros.findAll().toList().single()
        assertThat(row.contentMapId).isEqualTo(world.contentMapId)
        assertThat(row.name).isEqualTo(MACRO)
        assertThat(row.source).contains("def $MACRO")
        assertThat(readParameters(row.parameterNames)).containsExactly("card_a", "card_b")

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo(MacroWriteFrames.WRITE_RESULT)
        assertThat(answer.correlationId).describedAs("correlationId 가 요청의 messageId 를 문다").isNotNull()
        assertThat(answer.payload.path("type").asText()).isEqualTo(MacroWriteFrames.REGISTER)
        assertThat(answer.payload.path("created").asBoolean()).isTrue()
        // 64비트 id 가 JSON 숫자로 나가면 자바스크립트 소비자에서 깎인다. 문자열이어야 한다.
        assertThat(answer.payload.path("macro_id").isTextual).isTrue()
        assertThat(answer.payload.path("macro_id").asText()).isEqualTo(row.id.toString())
        assertThat(answer.payload.path("screen_ids").map { it.asText() })
            .containsExactly(world.screenA.toString())
    }

    @Test
    fun `저장된 정의를 이름으로 읽어 온다`(): Unit = runBlocking {
        val world = newWorld()
        register(world, name = MACRO, screens = listOf(world.screenA, world.screenB))
        val macroId = macros.findAll().toList().single().id!!
        recorder.sent.clear()

        read(world, MACRO)

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo(MacroWriteFrames.READ_RESULT)
        assertThat(answer.payload.path("macro_id").asText()).isEqualTo(macroId.toString())
        assertThat(answer.payload.path("name").asText()).isEqualTo(MACRO)
        // 원본 텍스트가 그대로 돌아온다. 들여쓰기가 Python 문법의 일부라 깎으면 다시 파싱되지 않는다.
        assertThat(answer.payload.path("source").asText()).isEqualTo(sourceOf(MACRO))
        assertThat(answer.payload.path("parameters").map { it.asText() })
            .containsExactly("card_a", "card_b")
        assertThat(answer.payload.path("screen_ids").map { it.asText() })
            .containsExactlyInAnyOrder(world.screenA.toString(), world.screenB.toString())
        // 저장된 tree 그대로다. 실행하는 쪽이 이것만 본다.
        val statements = answer.payload.path("definition").path("defs").path(0).path("statements")
        assertThat(statements.path(0).path("kind").asText()).isEqualTo("require")
        assertThat(statements.path(0).path("remedy").asText()).isEqualTo("전투 화면으로 먼저 간다")
    }

    /**
     * 이 스위트가 존재하는 두 번째 이유. `register_macro` 는 같은 이름의 행을 지우고 새로 넣지 않고
     * 갱신한다 — 지우고 넣으면 `ON DELETE CASCADE` 로 `screen_macro` 행이 같이 사라지고, 그것은
     * agent 가 공들여 단 것이다.
     */
    @Test
    fun `같은 이름을 다시 등록하면 제자리에서 갱신되고 관계 행이 남는다`(): Unit = runBlocking {
        val world = newWorld()
        register(world, name = MACRO, screens = listOf(world.screenA, world.screenB))
        val before = macros.findAll().toList().single()
        recorder.sent.clear()

        register(world, name = MACRO, source = "def $MACRO(card_a: string): pass", parameters = listOf("card_a"))

        val after = macros.findAll().toList().single()
        assertThat(after.id).describedAs("id 가 바뀌면 관계 행이 CASCADE 로 사라진다").isEqualTo(before.id)
        assertThat(after.source).isEqualTo("def $MACRO(card_a: string): pass")
        assertThat(readParameters(after.parameterNames)).containsExactly("card_a")
        assertThat(after.createdAt).describedAs("처음 등록한 시점은 고쳤다고 바뀌지 않는다").isEqualTo(before.createdAt)
        assertThat(after.updatedAt).isAfterOrEqualTo(before.updatedAt)

        // `screens` 를 안 줬는데도 앞서 단 관계 둘이 그대로다.
        assertThat(screenMacros.findScreenIdsByMacroId(after.id!!).toList())
            .containsExactlyInAnyOrder(world.screenA, world.screenB)
        assertThat(recorder.sent.single().payload.path("created").asBoolean())
            .describedAs("갱신이면 created 가 false 다").isFalse()
    }

    @Test
    fun `screens 를 주면 기존 관계에 더하고 기존 것을 지우지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        register(world, name = MACRO, screens = listOf(world.screenA))
        val macroId = macros.findAll().toList().single().id!!

        register(world, name = MACRO, screens = listOf(world.screenB))

        assertThat(screenMacros.findScreenIdsByMacroId(macroId).toList())
            .describedAs("관계를 빼는 길은 v1 에 없다. 더하기만 한다")
            .containsExactlyInAnyOrder(world.screenA, world.screenB)
    }

    @Test
    fun `같은 screen 을 두 번 달아도 관계 행이 둘이 되지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        register(world, name = MACRO, screens = listOf(world.screenA, world.screenA))
        val macroId = macros.findAll().toList().single().id!!

        register(world, name = MACRO, screens = listOf(world.screenA))

        assertThat(screenMacros.findScreenIdsByMacroId(macroId).toList()).containsExactly(world.screenA)
    }

    /** 어느 `screen` 과도 안 이어진 macro 는 아직 어디서 쓸지 모른다는 뜻이고, 허용된다. */
    @Test
    fun `screen 을 지목하지 않아도 등록된다`(): Unit = runBlocking {
        val world = newWorld()

        register(world, name = MACRO, screens = emptyList())

        val macroId = macros.findAll().toList().single().id!!
        assertThat(screenMacros.findScreenIdsByMacroId(macroId).toList()).isEmpty()
        assertThat(recorder.sent.single().type).isEqualTo(MacroWriteFrames.WRITE_RESULT)
    }

    /**
     * `remedy` 없는 `require` 는 agent 에게 "이 조건이 안 맞았다" 까지만 말하고 무엇을 하면 맞는지는
     * 말하지 않는다. **서비스가 거절한다** — DB 제약에 맡기면 제약 이름만 실린 예외가 돌아가고,
     * agent 는 무엇을 고쳐야 하는지 읽을 수 없다.
     */
    @Test
    fun `remedy 없는 require 는 사유와 함께 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        deliver(
            world,
            MacroWriteFrames.REGISTER,
            """
            {"name":"$MACRO","source":"def $MACRO(): pass","parameters":[],
             "definition":{"defs":[{"name":"$MACRO","statements":[
               {"kind":"require","condition":{"op":"eq"}}]}]}}
            """.trimIndent()
        )

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        val message = answer.payload.path("message").asText()
        assertThat(message).startsWith(MacroWriteFrames.REGISTER)
        assertThat(message).contains("every require statement must carry a remedy")
        assertThat(macros.findAll().toList()).isEmpty()
        // 사람이 볼 흔적도 남는다. 조용히 버리지 않는다.
        assertThat(qaLogs.findAll().toList().count { it.type == "ERROR" }).isEqualTo(1)
    }

    /** `if` 몸통 안에 중첩된 `require` 도 같은 판정을 받는다. 고정 경로로는 닿지 않는 자리다. */
    @Test
    fun `if 몸통 안의 remedy 없는 require 도 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        deliver(
            world,
            MacroWriteFrames.REGISTER,
            """
            {"name":"$MACRO","source":"def $MACRO(): pass","parameters":[],
             "definition":{"defs":[{"name":"$MACRO","statements":[
               {"kind":"if","condition":{"op":"gt"},"body":[
                 {"kind":"require","condition":{"op":"eq"},"remedy":"   "}]}]}]}}
            """.trimIndent()
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("every require statement must carry a remedy")
        assertThat(macros.findAll().toList()).isEmpty()
    }

    /**
     * **서비스를 지나지 않는 쓰기도 막힌다.** repository 를 직접 찔러 `ck_macro_require_carries_remedy`
     * 가 실제로 걸리는지 본다. 이것이 jsonpath 와 `"^\s*$"` 이스케이프가 Flyway 와 R2DBC 를
     * 지나서도 작동함을 증명하는 유일한 테스트다.
     *
     * 양성 대조를 함께 둔다. ARTEL-918 이 node 의 `kind` 나 `remedy` key 이름을 바꾸면 CHECK 가
     * 아무것도 막지 않게 되는데(`NOT jsonb_path_exists(...)` 가 어떤 tree 에도 참이 된다), 그때
     * 깨지는 것은 양성 쪽이 아니라 **음성 쪽**이라 조용히 지나가지 않는다.
     */
    @Test
    fun `DB CHECK 가 remedy 없는 require 를 막고 멀쩡한 것은 통과시킨다`(): Unit = runBlocking {
        val world = newWorld()

        // 양성 대조 — `remedy` 를 든 `require` 는 들어간다.
        val macroId = macros.upsertByName(
            contentMapId = world.contentMapId,
            name = "with_remedy",
            source = "def with_remedy(): pass",
            definitionJson = Json.of(
                """{"defs":[{"statements":[{"kind":"require","remedy":"대화창을 닫는다"}]}]}"""
            ),
            parameterNames = Json.of("[]"),
        )
        assertThat(macros.findById(macroId)).isNotNull()

        // 음성 대조 — `remedy` 가 공백뿐이면 DB 가 막는다.
        assertThatThrownBy {
            runBlocking {
                macros.upsertByName(
                    contentMapId = world.contentMapId,
                    name = "blank_remedy",
                    source = "def blank_remedy(): pass",
                    definitionJson = Json.of("""{"defs":[{"statements":[{"kind":"require","remedy":"  "}]}]}"""),
                    parameterNames = Json.of("[]"),
                )
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // 음성 대조 — `remedy` 칸이 아예 없어도 막는다.
        assertThatThrownBy {
            runBlocking {
                macros.upsertByName(
                    contentMapId = world.contentMapId,
                    name = "no_remedy",
                    source = "def no_remedy(): pass",
                    definitionJson = Json.of("""{"defs":[{"statements":[{"kind":"require"}]}]}"""),
                    parameterNames = Json.of("[]"),
                )
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(macros.findAll().toList().map { it.name }).containsExactly("with_remedy")
    }

    @Test
    fun `모르는 이름을 읽으면 어느 이름인지와 함께 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        read(world, "never_registered")

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.payload.path("message").asText())
            .isEqualTo("${MacroWriteFrames.READ} references an unknown macro: never_registered")
    }

    /** 이름의 유일 범위가 `content_map_id` 안이므로 다른 build 의 같은 이름은 찾지 않는다. */
    @Test
    fun `다른 build 에 등록된 같은 이름은 읽히지 않는다`(): Unit = runBlocking {
        val other = newWorld()
        register(other, name = MACRO, screens = emptyList())
        val world = newWorld()
        recorder.sent.clear()

        read(world, MACRO)

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("references an unknown macro: $MACRO")
    }

    @Test
    fun `다른 build 의 screen 을 지목하면 그 id 와 함께 거절된다`(): Unit = runBlocking {
        val other = newWorld()
        val world = newWorld()
        recorder.sent.clear()

        register(world, name = MACRO, screens = listOf(other.screenA))

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.payload.path("message").asText())
            .contains("references screens outside this build's content map: [${other.screenA}]")
        assertThat(macros.findAll().toList()).isEmpty()
    }

    @Test
    fun `name 이 없으면 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        deliver(
            world,
            MacroWriteFrames.REGISTER,
            """{"source":"def x(): pass","definition":{"defs":[]}}"""
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .isEqualTo("${MacroWriteFrames.REGISTER} payload.name is required")
    }

    @Test
    fun `definition 이 객체가 아니면 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        deliver(
            world,
            MacroWriteFrames.REGISTER,
            """{"name":"$MACRO","source":"def $MACRO(): pass","definition":[]}"""
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .isEqualTo("${MacroWriteFrames.REGISTER} payload.definition must be a JSON object")
    }

    /**
     * 지금 있는 라우터 가드 둘이 macro 프레임에도 그대로 걸린다.
     *
     * **outbound 프레임이 없다**는 것이 "답 없이 버린다" 의 뜻이다. `qa_log` 행은 남는다 —
     * 비 UUID 쪽은 `appendError` 가 감사 로그를 적기 때문이고, 그것을 없다고 단정하면 안 된다.
     */
    @Test
    fun `비 UUID messageId 는 답 없이 떨어진다`(): Unit = runBlocking {
        val world = newWorld()

        router.handle(
            QaAgentEnvelope(
                messageId = "not-a-uuid",
                type = MacroWriteFrames.REGISTER,
                qaTryId = world.qaTryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-10-06T00:00:00Z"),
                payload = objectMapper.readTree(registerPayload(MACRO, sourceOf(MACRO), listOf("card_a"), emptyList()))
            )
        )

        assertThat(recorder.sent).isEmpty()
        assertThat(macros.findAll().toList()).isEmpty()
    }

    @Test
    fun `끝난 try 가 보낸 프레임은 답 없이 떨어진다`(): Unit = runBlocking {
        val world = newWorld()
        // `ck_qa_try_completed_at` 이 끝난 상태에 `completed_at` 을 요구한다.
        qaTries.save(
            qaTries.findById(world.qaTryId)!!.copy(status = "COMPLETED", completedAt = Instant.now())
        )

        register(world, name = MACRO, screens = emptyList())

        assertThat(recorder.sent).isEmpty()
        assertThat(macros.findAll().toList()).isEmpty()
    }

    /** 모르는 타입은 지금까지처럼 거절된다 — macro 타입 둘이 `SUPPORTED_TYPES` 에 들었는지 보는 대조다. */
    @Test
    fun `macro 타입 둘은 지원 타입 집합에 들어 있다`(): Unit = runBlocking {
        val world = newWorld()

        deliver(world, "MACRO_NOPE", """{"name":"$MACRO"}""")

        assertThat(recorder.sent).describedAs("모르는 타입은 ERROR 프레임을 받지 않는다").isEmpty()
        assertThat(qaLogs.findAll().toList().map { it.message })
            .anyMatch { it != null && it.contains("Unsupported Agent message type: MACRO_NOPE") }
    }

    private fun sourceOf(name: String) =
        "def $name(card_a: string, card_b: string):\n" +
            "    require(scene() == \"$BATTLE\", \"전투 화면으로 먼저 간다\")\n" +
            "    button_click(find(label=card_a))\n"

    private fun registerPayload(
        name: String,
        source: String,
        parameters: List<String>,
        screens: List<Long>,
    ): String {
        val definition = """
            {"entrypoint":"$name","defs":[{"name":"$name",
              "parameters":[{"name":"card_a","type":"string"},{"name":"card_b","type":"string"}],
              "statements":[
                {"kind":"require","condition":{"op":"eq"},"remedy":"전투 화면으로 먼저 간다"},
                {"kind":"call","tool":"button_click"}]}]}
        """.trimIndent()
        val node = objectMapper.createObjectNode()
            .put("name", name)
            .put("source", source)
        node.set<com.fasterxml.jackson.databind.JsonNode>("definition", objectMapper.readTree(definition))
        node.putArray("parameters").apply { parameters.forEach { add(it) } }
        node.putArray("screens").apply { screens.forEach { add(it.toString()) } }
        return objectMapper.writeValueAsString(node)
    }

    private suspend fun register(
        world: World,
        name: String,
        source: String = sourceOf(name),
        parameters: List<String> = listOf("card_a", "card_b"),
        screens: List<Long> = emptyList(),
    ) = deliver(world, MacroWriteFrames.REGISTER, registerPayload(name, source, parameters, screens))

    private suspend fun read(world: World, name: String) =
        deliver(world, MacroWriteFrames.READ, """{"name":"$name"}""")

    private suspend fun deliver(world: World, type: String, payload: String) {
        router.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = world.qaTryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-10-06T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private fun readParameters(stored: Json): List<String> =
        objectMapper.readTree(stored.asString()).map { it.asText() }

    private suspend fun newWorld(agentSessionId: String? = SESSION_ID): World {
        val now = Instant.now()
        val userId = signIn().userId.toLong()
        val project = projects.save(
            ProjectEntity(name = "macro-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        )!!
        val instance = gameInstances.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                lastGameBuildId = build.id,
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
            )
        )!!
        val contentMap = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "macro-write",
            )
        )!!
        val sceneId = scenes.save(
            SceneEntity(contentMapId = contentMap.id!!, name = BATTLE, walked = true)
        )!!.id!!
        val screenA = screens.save(
            ScreenEntity(sceneId = sceneId, discriminator = Json.of("""[{"selector":"Canvas[0]/Hand","active":true}]"""))
        )!!.id!!
        val screenB = screens.save(
            ScreenEntity(sceneId = sceneId, discriminator = Json.of("""[{"selector":"Canvas[0]/Zone","active":true}]"""))
        )!!.id!!
        val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런"))!!.id!!
        val qaRun = qaRuns.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = userId,
                status = "RUNNING",
                startedAt = now,
            )
        )!!
        val scenario = testScenarios.save(TestScenarioEntity(projectId = project.id!!))!!
        val qaTry = qaTries.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                qaRunId = qaRun.id,
                startedBy = userId,
                agentSessionId = agentSessionId,
                status = "RUNNING",
                model = "claude-sonnet-4",
                promptVersion = "v3",
                runConfig = Json.of("{}"),
                startedAt = now,
            )
        )!!
        return World(
            contentMapId = contentMap.id!!,
            sceneId = sceneId,
            screenA = screenA,
            screenB = screenB,
            qaTryId = qaTry.id!!,
        )
    }

    private suspend fun signIn(): AuthenticatedUser {
        val seed = UUID.randomUUID().toString().take(8)
        return oauthUsers.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "user-$seed@example.com",
            )
        )
    }

    private data class World(
        val contentMapId: Long,
        val sceneId: Long,
        val screenA: Long,
        val screenB: Long,
        val qaTryId: Long,
    )
}
