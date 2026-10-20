package kr.artel.orchestration.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaFunctionOptions
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.qa.service.FunctionMode
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.sdk.service.handler.BehaviorEndedMessageHandler
import kr.artel.orchestration.sdk.service.handler.GameFunctionCatalogMessageHandler
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseFunctions
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 게임이 등록한 cheat · behavior 가 빌드에 저장되고, run 옵션으로 걸러져 agent 에게 가고, 허용 밖의 호출이 막히는지
 * (ARTEL-1053).
 *
 * 계약은 `.plan/general/2026-10-14-game-functions-wire-contract.md` 다. 여기서 지키는 것은 다섯이다: 프레임이 빌드에 저장되고
 * 엔드포인트가 그대로 돌려준다, 목록을 보낸 적 없는 빌드는 404 가 아니다, run 옵션이 기본값 `on` 으로 `run_config` 에 남고 세션
 * 개설에 거른 목록이 실린다, 허용 밖의 `invoke_cheat` 는 SDK 에 닿지 않고 agent 가 실패를 받는다, 종료 프레임과 스텝의 사용
 * 기록이 그대로 남는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameFunctionsIntegrationTest {

    class RecordingAgentPort : QaAgentPort {
        val contexts: MutableList<QaAgentSessionContext> = CopyOnWriteArrayList()
        val sent: MutableList<QaAgentEnvelope> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession {
            contexts += context
            return QaAgentSession("game-functions-session-${contexts.size}")
        }

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
            sent += envelope
        }

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun recordingAgentPort() = RecordingAgentPort()
    }

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var catalogHandler: GameFunctionCatalogMessageHandler
    @Autowired private lateinit var behaviorEndedHandler: BehaviorEndedMessageHandler
    @Autowired private lateinit var qaTryService: QaTryService
    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var bridge: QaSdkBridgeService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var composition: ScenarioCompositionService
    @Autowired private lateinit var agentPort: QaAgentPort
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort
    private val socket: WebSocketSession = Mockito.mock(WebSocketSession::class.java)

    @BeforeEach
    fun cleanBefore(): Unit = runBlocking {
        wipe()
        recorder.contexts.clear()
        recorder.sent.clear()
    }

    @AfterEach
    fun cleanAfter(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        runScenarioRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        testCaseRepository.deleteAll()
        gameBuildRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ 카탈로그 저장과 엔드포인트

    @Test
    fun `GAME_FUNCTION_CATALOG 프레임이 빌드에 저장되고 엔드포인트가 그대로 돌려준다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)

        val body = get(seed.token, "/api/projects/${seed.projectId}/game-builds/${seed.buildId}/game-functions")

        assertThat(body["revision"].asInt()).isEqualTo(3)
        assertThat(body["receivedAt"].asText()).isNotBlank()
        assertThat(body["cheats"].map { it["name"].asText() }).containsExactly("give_gold", "skip_tutorial")
        assertThat(body["cheats"][0]["params"][0]["name"].asText()).isEqualTo("amount")
        assertThat(body["cheats"][0]["params"][0]["max"].asInt()).isEqualTo(1000000)
        assertThat(body["behaviors"].map { it["name"].asText() }).containsExactly("auto_attack", "flee")
        assertThat(body.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("revision", "receivedAt", "cheats", "behaviors")
    }

    @Test
    fun `새 프레임은 앞 목록을 덮어쓴다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)
        catalogHandler.handle(
            seed.instanceId.toString(),
            """{"type":"GAME_FUNCTION_CATALOG","revision":1,"cheats":[],"behaviors":[${entry("flee")}]}""",
            socket
        )

        val body = get(seed.token, "/api/projects/${seed.projectId}/game-builds/${seed.buildId}/game-functions")

        assertThat(body["revision"].asInt()).isEqualTo(1)
        assertThat(body["cheats"]).isEmpty()
        assertThat(body["behaviors"].map { it["name"].asText() }).containsExactly("flee")
    }

    @Test
    fun `목록을 보낸 적 없는 빌드는 revision 0 과 빈 목록 둘로 200 을 답한다`(): Unit = runBlocking {
        val seed = seed()

        val body = get(seed.token, "/api/projects/${seed.projectId}/game-builds/${seed.buildId}/game-functions")

        assertThat(body["revision"].asInt()).isZero()
        assertThat(body["receivedAt"].isNull).isTrue()
        assertThat(body["cheats"]).isEmpty()
        assertThat(body["behaviors"]).isEmpty()
    }

    @Test
    fun `프로젝트 참여자가 아니면 404 다`(): Unit = runBlocking {
        val seed = seed()
        val stranger = jwtService.issue(signIn("stranger"))

        val error = errorOf { get(stranger, "/api/projects/${seed.projectId}/game-builds/${seed.buildId}/game-functions") }

        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `깨진 프레임은 저장하지 않고 이름 없는 항목은 버린다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), """{"type":"GAME_FUNCTION_CATALOG","cheats":[]}""", socket)
        assertThat(gameBuildRepository.findById(seed.buildId)!!.gameFunctionCatalog).isNull()

        catalogHandler.handle(
            seed.instanceId.toString(),
            """{"type":"GAME_FUNCTION_CATALOG","revision":2,"cheats":[{"description":"이름 없음"},${entry("give_gold")}],"behaviors":"x"}""",
            socket
        )

        val body = get(seed.token, "/api/projects/${seed.projectId}/game-builds/${seed.buildId}/game-functions")
        assertThat(body["cheats"].map { it["name"].asText() }).containsExactly("give_gold")
        assertThat(body["behaviors"]).isEmpty()
    }

    // ------------------------------------------------------------------ run 옵션과 세션 개설

    @Test
    fun `옵션을 안 주면 on 이고 카탈로그 전부가 세션 개설과 run_config 에 실린다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)

        val response = qaTryService.create(seed.scenarioId, seed.instanceId, seed.ownerId)

        assertThat(response.cheatMode).isEqualTo("on")
        assertThat(response.behaviorMode).isEqualTo("on")
        assertThat(response.allowedCheats).isNull()
        assertThat(response.allowedBehaviors).isNull()
        assertThat(response.effectiveCheats).containsExactly("give_gold", "skip_tutorial")
        assertThat(response.effectiveBehaviors).containsExactly("auto_attack", "flee")
        val config = response.runConfig!!
        assertThat(config["cheat_mode"].asText()).isEqualTo("on")
        assertThat(config["behavior_mode"].asText()).isEqualTo("on")
        assertThat(config.has("allowed_cheats")).isFalse()
        val context = recorder.contexts.single()
        assertThat(context.cheats!!.map { it["name"].asText() }).containsExactly("give_gold", "skip_tutorial")
        assertThat(context.behaviors!!.map { it["name"].asText() }).containsExactly("auto_attack", "flee")
        // 세션이 붙으면서 run_config 가 Agent 스냅샷으로 갈아치워져도 옵션은 남는다.
        assertThat(qaTryService.get(response.id.toLong(), seed.ownerId)!!.effectiveCheats)
            .containsExactly("give_gold", "skip_tutorial")
    }

    @Test
    fun `off 와 허용 목록이 걸러진 결과가 목록과 응답에 같이 나타난다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)

        val response = qaTryService.create(
            seed.scenarioId, seed.instanceId, seed.ownerId,
            functionOptions = QaFunctionOptions(
                cheatMode = FunctionMode.OFF,
                allowedCheats = listOf("give_gold"),
                allowedBehaviors = listOf("flee")
            )
        )

        assertThat(response.cheatMode).isEqualTo("off")
        assertThat(response.allowedCheats).containsExactly("give_gold")
        assertThat(response.allowedBehaviors).containsExactly("flee")
        assertThat(response.effectiveCheats).isEmpty()
        assertThat(response.effectiveBehaviors).containsExactly("flee")
        val context = recorder.contexts.single()
        assertThat(context.cheats).isEmpty()
        assertThat(context.behaviors!!.map { it["name"].asText() }).containsExactly("flee")
    }

    @Test
    fun `빌드에 없는 이름을 허용 목록에 쓰면 400 이고 try 행이 남지 않는다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)

        assertThatThrownBy {
            runBlocking {
                qaTryService.create(
                    seed.scenarioId, seed.instanceId, seed.ownerId,
                    functionOptions = QaFunctionOptions(allowedCheats = listOf("give_golds"))
                )
            }
        }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("give_golds")

        assertThat(qaTryRepository.findAll().toList()).isEmpty()
        assertThat(recorder.contexts).isEmpty()
    }

    @Test
    fun `목록을 보낸 적 없는 빌드의 run 은 빈 목록으로 시작한다`(): Unit = runBlocking {
        val seed = seed()

        val response = qaTryService.create(seed.scenarioId, seed.instanceId, seed.ownerId)

        assertThat(response.effectiveCheats).isEmpty()
        val context = recorder.contexts.single()
        assertThat(context.cheats).isEmpty()
        assertThat(context.behaviors).isEmpty()
    }

    @Test
    fun `run 경로도 모든 try 에 같은 옵션을 싣는다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)

        val run = qaTryService.createRun(
            seed.testRunId, seed.instanceId, seed.ownerId,
            functionOptions = QaFunctionOptions(behaviorMode = FunctionMode.OFF)
        )

        assertThat(run.tries).hasSize(2)
        assertThat(run.tries.map { it.behaviorMode }).containsOnly("off")
        assertThat(run.tries.map { it.effectiveBehaviors }).containsOnly(emptyList<String>())
        assertThat(run.tries.map { it.effectiveCheats }).containsOnly(listOf("give_gold", "skip_tutorial"))
        // 두 번째 try 는 아직 PENDING 이지만 옵션을 이미 들고 있다.
        val second = qaTryRepository.findById(run.tries[1].id.toLong())!!
        assertThat(objectMapper.readTree(second.runConfig.asString())["cheat_mode"].asText()).isEqualTo("on")
        assertThat(recorder.contexts.single().behaviors).isEmpty()
    }

    // ------------------------------------------------------------------ 허용 검사

    @Test
    fun `허용 목록에 없는 invoke_cheat 는 SDK 로 가지 않고 agent 가 실패를 받는다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)
        val toSdk = seed.toSdk
        val started = qaTryService.create(
            seed.scenarioId, seed.instanceId, seed.ownerId,
            functionOptions = QaFunctionOptions(allowedCheats = listOf("give_gold"))
        )
        val tryId = started.id.toLong()
        toSdk.clear()
        val agentMessageId = UUID.randomUUID().toString()

        router.handle(
            actionFrame(
                tryId, agentMessageId,
                """{"id":1,"jsonrpc":"2.0","method":"invoke_cheat","params":[{"name":"skip_tutorial","args":{}}]}"""
            )
        )

        assertThat(toSdk).isEmpty()
        assertThat(sdkActionLogs(tryId)).isEmpty()
        val answer = recorder.sent.single { it.type == "ACTION_RESULT" }
        assertThat(answer.correlationId).isEqualTo(agentMessageId)
        val result = answer.payload["results"].single()
        assertThat(result["id"].asLong()).isEqualTo(1)
        assertThat(result["success"].asBoolean()).isFalse()
        assertThat(result["action"].asText()).isEqualTo("invoke_cheat")
        assertThat(result["error"].asText()).contains("skip_tutorial", "give_gold")
    }

    @Test
    fun `허용된 이름과 이름이 없는 stop_behavior 는 그대로 SDK 로 간다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)
        val toSdk = seed.toSdk
        val started = qaTryService.create(seed.scenarioId, seed.instanceId, seed.ownerId)
        val tryId = started.id.toLong()
        toSdk.clear()

        router.handle(
            actionFrame(
                tryId, UUID.randomUUID().toString(),
                """{"id":1,"jsonrpc":"2.0","method":"invoke_cheat","params":[{"name":"give_gold","args":{"amount":500}}]}""",
                """{"id":2,"jsonrpc":"2.0","method":"stop_behavior","params":[{"behaviorId":7}]}"""
            )
        )

        assertThat(sdkActionLogs(tryId)).hasSize(1)
        assertThat(toSdk.filter { it.contains("invoke_cheat") && it.contains("stop_behavior") }).hasSize(1)
        assertThat(recorder.sent.filter { it.type == "ACTION_RESULT" }).isEmpty()
    }

    @Test
    fun `cheatMode 가 off 인 run 은 카탈로그에 있는 cheat 도 막는다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)
        val toSdk = seed.toSdk
        val started = qaTryService.create(
            seed.scenarioId, seed.instanceId, seed.ownerId,
            functionOptions = QaFunctionOptions(cheatMode = FunctionMode.OFF)
        )
        toSdk.clear()

        router.handle(
            actionFrame(
                started.id.toLong(), UUID.randomUUID().toString(),
                """{"id":1,"jsonrpc":"2.0","method":"start_behavior","params":[{"name":"auto_attack"}]}""",
                """{"id":2,"jsonrpc":"2.0","method":"invoke_cheat","params":[{"name":"give_gold"}]}"""
            )
        )

        // 배치 전체가 거절된다. 통과했을 start_behavior 도 실행하지 않았다고 답한다.
        assertThat(toSdk).isEmpty()
        val results = recorder.sent.single { it.type == "ACTION_RESULT" }.payload["results"]
        assertThat(results.map { it["success"].asBoolean() }).containsOnly(false)
        assertThat(results[0]["error"].asText()).contains("not run")
        assertThat(results[1]["error"].asText()).contains("give_gold")
    }

    // ------------------------------------------------------------------ BEHAVIOR_ENDED 와 STATUS

    @Test
    fun `BEHAVIOR_ENDED 는 로그 두 줄과 type 을 뺀 payload 로 agent 에게 간다`(): Unit = runBlocking {
        val seed = seed()
        catalogHandler.handle(seed.instanceId.toString(), catalogFrame(revision = 3), socket)
        val started = qaTryService.create(seed.scenarioId, seed.instanceId, seed.ownerId)
        val tryId = started.id.toLong()
        recorder.sent.clear()

        behaviorEndedHandler.handle(
            seed.instanceId.toString(),
            """{"type":"BEHAVIOR_ENDED","behaviorId":7,"name":"auto_attack","target":null,"reason":"completed","detail":"no enemy in range","seconds":12.4,"frame":18234}""",
            socket
        )

        val logs = qaLogRepository.findAll().toList().filter { it.qaTryId == tryId && it.type == "BEHAVIOR_ENDED" }
        assertThat(logs.map { it.direction }).containsExactlyInAnyOrder("SDK_TO_ORCHE", "ORCHE_TO_AGENT")
        val envelope = recorder.sent.single { it.type == "BEHAVIOR_ENDED" }
        assertThat(envelope.qaTryId).isEqualTo(started.id)
        assertThat(envelope.payload.has("type")).isFalse()
        assertThat(envelope.payload["behaviorId"].asInt()).isEqualTo(7)
        assertThat(envelope.payload["reason"].asText()).isEqualTo("completed")
        assertThat(envelope.payload["detail"].asText()).isEqualTo("no enemy in range")
        assertThat(envelope.payload["seconds"].asDouble()).isEqualTo(12.4)
        assertThat(envelope.payload["frame"].asInt()).isEqualTo(18234)
    }

    @Test
    fun `활성 try 가 없으면 BEHAVIOR_ENDED 는 아무 데도 쓰지 않는다`(): Unit = runBlocking {
        val seed = seed()

        val routed = bridge.routeBehaviorEnded(
            seed.instanceId,
            """{"type":"BEHAVIOR_ENDED","behaviorId":1,"name":"x","reason":"stopped","seconds":1.0,"frame":2}"""
        )

        assertThat(routed).isFalse()
        assertThat(qaLogRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `스텝 STATUS 의 used_cheats 와 active_behaviors 는 payload 에 그대로 남는다`(): Unit = runBlocking {
        val seed = seed()
        val started = qaTryService.create(seed.scenarioId, seed.instanceId, seed.ownerId)
        val tryId = started.id.toLong()
        val usedCheats = """[{"name":"give_gold","args":{"amount":500},"target":null,"success":true}]"""
        val activeBehaviors = """[{"behaviorId":7,"name":"auto_attack","target":null,"seconds":8.0}]"""

        router.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = "STATUS",
                qaTryId = started.id,
                timestamp = Instant.now(),
                payload = objectMapper.readTree(
                    """{"status":"COMPLETED","step":1,"message":"step 1","used_cheats":$usedCheats,"active_behaviors":$activeBehaviors}"""
                )
            )
        )

        val stored = qaLogRepository.findAll().toList()
            .single { it.qaTryId == tryId && it.type == "STATUS" && it.direction == "AGENT_TO_ORCHE" }
        val payload = objectMapper.readTree(stored.payload.asString())
        assertThat(payload["used_cheats"]).isEqualTo(objectMapper.readTree(usedCheats))
        assertThat(payload["active_behaviors"]).isEqualTo(objectMapper.readTree(activeBehaviors))
    }

    // ------------------------------------------------------------------ test case 의 functions

    @Test
    fun `test case 의 functions 는 저장되고 읽히고 AgentCase 로 간다`(): Unit = runBlocking {
        val seed = seed()
        val functions = TestCaseFunctions(
            cheats = objectMapper.readTree("""["give_gold"]"""),
            behaviors = objectMapper.readTree(""""none"""")
        )
        val created = testCaseService.createTestCase(
            seed.projectId,
            seed.ownerId,
            TestCaseCreateRequest(scene = "상점", step = "구매", expectedValue = "골드가 줄어든다", functions = functions)
        )!!

        assertThat(created.functions!!.cheats).isEqualTo(objectMapper.readTree("""["give_gold"]"""))
        assertThat(created.functions!!.behaviors).isEqualTo(objectMapper.readTree(""""none""""))
        val detail = testCaseService.getTestCase(created.id.toLong(), seed.ownerId)!!
        assertThat(detail.functions).isEqualTo(created.functions)

        val agentCase = composition.agentScenario(draftFor(created.id.toLong())).steps.single().case!!
        assertThat(objectMapper.valueToTree<JsonNode>(agentCase)["functions"]).isEqualTo(
            objectMapper.readTree("""{"cheats":["give_gold"],"behaviors":"none"}""")
        )
    }

    @Test
    fun `functions 를 정하지 않은 test case 는 AgentCase 에 키를 싣지 않고 수정이 지우지도 않는다`(): Unit = runBlocking {
        val seed = seed()
        val plain = testCaseService.createTestCase(
            seed.projectId, seed.ownerId, TestCaseCreateRequest(scene = "S", step = "a", expectedValue = "b")
        )!!
        assertThat(plain.functions).isNull()
        val agentCase = composition.agentScenario(draftFor(plain.id.toLong())).steps.single().case!!
        assertThat(objectMapper.valueToTree<JsonNode>(agentCase).has("functions")).isFalse()

        testCaseService.updateTestCase(
            plain.id.toLong(), seed.ownerId,
            TestCaseUpdateRequest(functions = TestCaseFunctions(cheats = objectMapper.readTree(""""none"""")))
        )
        val unrelated = testCaseService.updateTestCase(plain.id.toLong(), seed.ownerId, TestCaseUpdateRequest(step = "c"))!!

        assertThat(unrelated.step).isEqualTo("c")
        assertThat(unrelated.functions!!.cheats).isEqualTo(objectMapper.readTree(""""none""""))
    }

    @Test
    fun `모르는 값이나 빈 이름이 든 functions 는 400 이다`(): Unit = runBlocking {
        val seed = seed()
        for (bad in listOf(""""every"""", """[""]""", """[1]""", """{"a":1}""")) {
            assertThatThrownBy {
                runBlocking {
                    testCaseService.createTestCase(
                        seed.projectId, seed.ownerId,
                        TestCaseCreateRequest(
                            scene = "S", step = "a", expectedValue = "b",
                            functions = TestCaseFunctions(cheats = objectMapper.readTree(bad))
                        )
                    )
                }
            }.describedAs(bad).isInstanceOf(BadRequestException::class.java)
        }
    }

    // ------------------------------------------------------------------ 준비

    private data class Seed(
        val ownerId: Long,
        val token: String,
        val projectId: Long,
        val buildId: Long,
        val instanceId: Long,
        val scenarioId: Long,
        val testRunId: Long,
        /** 이 인스턴스의 SDK 소켓으로 나가는 프레임. 실제로 SDK 가 받는 것은 이 리스트다. */
        val toSdk: CopyOnWriteArrayList<String>
    )

    private suspend fun seed(): Seed {
        val owner = signIn("owner")
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "functions-p", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        val build = gameBuildRepository.save(
            GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now, updatedAt = now)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "inst", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
                lastGameBuildId = build.id, createdAt = now, updatedAt = now
            )
        )!!
        val testRun = testRunRepository.save(TestRunEntity(projectId = projectId, name = "런"))!!
        val s1 = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))!!
        val s2 = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))!!
        testRunService.setScenarios(testRun.id!!, ownerId, listOf(s1.id!!, s2.id!!))
        return Seed(
            ownerId, jwtService.issue(owner), projectId, build.id!!, instance.id!!, s1.id!!, testRun.id!!,
            connectSdk(instance.id!!)
        )
    }

    private suspend fun signIn(login: String) =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = UUID.randomUUID().toString(),
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )!!

    private fun draftFor(caseId: Long) =
        ScenarioDraft(title = "t", description = "", steps = listOf(ScenarioStep(action = "1", caseId = caseId)))

    /** 인스턴스에 mock 소켓을 붙인다. 붙어 있지 않으면 `create` 가 SDK 연결 없음으로 거절한다. */
    private fun connectSdk(instanceId: Long): CopyOnWriteArrayList<String> {
        val frames = CopyOnWriteArrayList<String>()
        val outbound = requireNotNull(sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java)))
        outbound.subscribe { frames.add(it) }
        return frames
    }

    private suspend fun sdkActionLogs(tryId: Long) =
        qaLogRepository.findAll().toList().filter { it.qaTryId == tryId && it.direction == "ORCHE_TO_SDK" && it.type == "ACTION" }

    private fun actionFrame(qaTryId: Long, messageId: String, vararg actions: String) = QaAgentEnvelope(
        messageId = messageId,
        type = "ACTION",
        qaTryId = qaTryId.toString(),
        timestamp = Instant.now(),
        payload = objectMapper.readTree("""{"message":"act","actions":[${actions.joinToString(",")}]}""")
    )

    private fun entry(name: String, params: String = "[]") =
        """{"name":"$name","description":"$name 설명","params":$params,"target":null,"group":null}"""

    private fun catalogFrame(revision: Int) =
        """
        {"type":"GAME_FUNCTION_CATALOG","revision":$revision,
         "cheats":[
           ${entry("give_gold", """[{"name":"amount","type":"int","min":1,"max":1000000,"default":null,"values":null}]""")},
           ${entry("skip_tutorial")}],
         "behaviors":[${entry("auto_attack")},${entry("flee")}]}
        """.trimIndent()

    private fun get(token: String, uri: String): JsonNode = objectMapper.readTree(
        WebClient.create("http://localhost:$port").get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
