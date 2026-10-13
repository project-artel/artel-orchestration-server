package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Readable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.macro.search.MacroEmbeddingBackfillWorker
import kr.artel.orchestration.contentmap.macro.search.MacroSearchFrames
import kr.artel.orchestration.contentmap.macro.search.MacroSearchProperties
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val SESSION_ID = "macro-search-session"
private const val BATTLE = "TurnBattleScene"

/** V102 의 vector(1024) 와 같아야 한다. 다르면 INSERT 가 거절된다. */
private const val DIMENSIONS = 1024

/**
 * `MACRO_SEARCH` 와 macro 임베딩 백필(ARTEL-986).
 *
 * 프레임은 라우터로 넣고 답은 [RecordingAgentPort] 가 받은 봉투로 읽는다. 그래서 wire 필드 이름과
 * correlation id 도 같은 테스트에서 본다. Agent `/embed` 는 [FakeEmbeddingClient] 대역이다 — 문장에
 * 들어 있는 낱말로 축 하나를 고르는 one-hot 벡터라, 같은 축이면 거리 0, 다른 축이면 거리 1이다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["artel.macro.search.query-timeout=PT0.5S"],
)
class MacroSearchIntegrationTest {

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

    /**
     * 문장에 [rules] 의 키가 들어 있으면 그 축의 one-hot 벡터를 준다. 어느 키도 없으면 900번 축이다.
     * [failure] 가 있으면 던지고, [hangMillis] 가 양수면 그만큼 기다린 뒤 답한다.
     */
    class FakeEmbeddingClient(private val model: String) : EmbeddingClient {
        var rules: Map<String, Int> = emptyMap()
        var failure: Exception? = null
        var hangMillis: Long = 0
        val embedCalls = AtomicInteger()

        override suspend fun embed(texts: List<String>): EmbedResponse {
            embedCalls.incrementAndGet()
            failure?.let { throw it }
            if (hangMillis > 0) delay(hangMillis)
            return EmbedResponse(
                model = model,
                dimensions = DIMENSIONS,
                vectors = texts.map { text ->
                    val lowered = text.lowercase()
                    val axis = rules.entries.firstOrNull { lowered.contains(it.key) }?.value ?: 900
                    List(DIMENSIONS) { index -> if (index == axis) 1.0 else 0.0 }
                }
            )
        }
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        @Primary
        fun recordingAgentPort(): QaAgentPort = RecordingAgentPort()

        @Bean
        @Primary
        fun fakeEmbeddingClient(properties: MacroSearchProperties): FakeEmbeddingClient =
            FakeEmbeddingClient(properties.model)
    }

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var worker: MacroEmbeddingBackfillWorker
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
    @Autowired private lateinit var fake: FakeEmbeddingClient
    @Autowired private lateinit var databaseClient: DatabaseClient

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort

    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        recorder.sent.clear()
        fake.rules = emptyMap()
        fake.failure = null
        fake.hangMillis = 0
        fake.embedCalls.set(0)
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
    fun `키워드로만 걸리는 macro 는 KEYWORD 로 돌아온다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "attack_enemy", "Attack one enemy with the equipped card.")
        macro(world, "open_shop", "Open the shop screen.")

        search(world, "ENEMY")

        val result = recorder.sent.single()
        assertThat(result.type).isEqualTo(MacroSearchFrames.SEARCH_RESULT)
        assertThat(names(result)).containsExactly("attack_enemy")
        assertThat(matchedBy(result, 0)).containsExactly("KEYWORD")
        assertThat(result.payload.path("vectorAvailable").asBoolean()).isTrue()
    }

    @Test
    fun `벡터로만 걸리는 macro 가 가장 앞에 서고 VECTOR 로 돌아온다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "restore_hp", "Heal the whole party.")
        macro(world, "open_shop", "Open the shop screen.")
        fake.rules = mapOf("heal" to 1, "recover" to 1, "shop" to 2)
        worker.runOnce()

        search(world, "recover health", limit = 1)

        val result = recorder.sent.single()
        assertThat(names(result)).containsExactly("restore_hp")
        assertThat(matchedBy(result, 0)).containsExactly("VECTOR")
    }

    @Test
    fun `두 목록에 모두 든 macro 가 먼저 오고 점수는 두 순위의 합이다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "cast_pair_at_enemy", "Cast the spell card and the element card at one enemy.")
        macro(world, "attack_enemy", "Attack one enemy.")
        macro(world, "heal_party", "Heal the whole party.")
        fake.rules = mapOf("cast" to 0, "heal" to 1)
        worker.runOnce()

        search(world, "cast enemy")

        val result = recorder.sent.single()
        assertThat(names(result)).containsExactly("cast_pair_at_enemy", "attack_enemy", "heal_party")
        assertThat(matchedBy(result, 0)).containsExactly("KEYWORD", "VECTOR")
        // 키워드 1위 + 벡터 1위, k = 60.
        assertThat(result.payload.path("items").path(0).path("score").asDouble()).isEqualTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-9))
        // attack_enemy: 키워드 2위(단어 enemy 하나) + 벡터 2위(거리 1, 이름순), heal_party: 벡터 3위만.
        assertThat(matchedBy(result, 1)).containsExactly("KEYWORD", "VECTOR")
        assertThat(matchedBy(result, 2)).containsExactly("VECTOR")
    }

    @Test
    fun `이름의 밑줄을 낱말 경계로 보고 찾는다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "cast_pair_at_enemy", null)
        macro(world, "open_shop", null)

        search(world, "cast pair")

        assertThat(names(recorder.sent.single())).containsExactly("cast_pair_at_enemy")
    }

    @Test
    fun `질의의 퍼센트 기호는 와일드카드가 아니라 글자로 찾는다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "big_hit", "Deals 100% damage.")
        macro(world, "small_hit", "Deals small damage.")

        search(world, "%")

        assertThat(names(recorder.sent.single())).containsExactly("big_hit")
    }

    @Test
    fun `질의의 밑줄과 역슬래시도 글자로 찾는다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "plain", "Path a\\b is used.")
        macro(world, "other", "Nothing here.")

        search(world, "a\\b")

        assertThat(names(recorder.sent.single())).containsExactly("plain")
    }

    @Test
    fun `다른 content map 의 macro 는 키워드로도 벡터로도 돌아오지 않는다`(): Unit = runBlocking {
        val mine = newWorld()
        val theirs = newWorld()
        macro(mine, "attack_mine", "Attack the enemy.")
        macro(theirs, "attack_theirs", "Attack the enemy.")
        fake.rules = mapOf("attack" to 0)
        worker.runOnce()

        search(mine, "attack enemy", limit = 10)

        assertThat(names(recorder.sent.single())).containsExactly("attack_mine")
    }

    @Test
    fun `질의 임베딩이 실패하면 키워드 결과만 vectorAvailable false 로 돌려준다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "attack_enemy", "Attack one enemy.")
        macro(world, "heal_party", "Heal the whole party.")
        fake.rules = mapOf("attack" to 0, "heal" to 1)
        worker.runOnce()
        fake.failure = IllegalStateException("embed down")

        search(world, "enemy")

        val result = recorder.sent.single()
        assertThat(result.type).isEqualTo(MacroSearchFrames.SEARCH_RESULT)
        assertThat(names(result)).containsExactly("attack_enemy")
        assertThat(matchedBy(result, 0)).containsExactly("KEYWORD")
        assertThat(result.payload.path("vectorAvailable").asBoolean()).isFalse()
    }

    @Test
    fun `질의 임베딩이 시간 안에 끝나지 않아도 키워드 결과로 답한다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "attack_enemy", "Attack one enemy.")
        fake.hangMillis = 30_000

        search(world, "enemy")

        val result = recorder.sent.single()
        assertThat(names(result)).containsExactly("attack_enemy")
        assertThat(result.payload.path("vectorAvailable").asBoolean()).isFalse()
    }

    @Test
    fun `limit 으로 자른다`(): Unit = runBlocking {
        val world = newWorld()
        (1..4).forEach { macro(world, "hit_$it", "Hit the enemy.") }

        search(world, "enemy", limit = 2)

        assertThat(names(recorder.sent.single())).containsExactly("hit_1", "hit_2")
    }

    @Test
    fun `프레임 필드 이름이 계약과 같다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = macro(world, "cast_pair_at_enemy", "Cast two cards at one enemy.", parameters = listOf("spell", "enemy"))
        screenMacros.link(world.screenA, macroId)

        val requestId = UUID.randomUUID().toString()
        deliver(world, requestId, """{"query":"cast","limit":3,"step":7}""")

        val result = recorder.sent.single()
        assertThat(result.type).isEqualTo("MACRO_SEARCH_RESULT")
        assertThat(result.correlationId).isEqualTo(requestId)
        assertThat(result.payload.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("items", "vectorAvailable")
        val item = result.payload.path("items").path(0)
        assertThat(item.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("name", "parameters", "docstring", "screens", "matchedBy", "score")
        assertThat(item.path("name").asText()).isEqualTo("cast_pair_at_enemy")
        assertThat(item.path("docstring").asText()).isEqualTo("Cast two cards at one enemy.")
        assertThat(item.path("parameters").map { it.path("name").asText() to it.path("type").asText() })
            .containsExactly("spell" to "object", "enemy" to "object")
        val screen = item.path("screens").path(0)
        assertThat(screen.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("screenId", "sceneName")
        assertThat(screen.path("screenId").isTextual).isTrue()
        assertThat(screen.path("screenId").asText()).isEqualTo(world.screenA.toString())
        assertThat(screen.path("sceneName").asText()).isEqualTo(BATTLE)
        assertThat(item.path("matchedBy").isArray).isTrue()
        assertThat(item.path("score").isNumber).isTrue()
        assertThat(result.payload.path("vectorAvailable").isBoolean).isTrue()
    }

    @Test
    fun `docstring 이 없는 macro 는 docstring 칸이 null 이다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "open_shop", null)

        search(world, "shop")

        val item = recorder.sent.single().payload.path("items").path(0)
        assertThat(item.has("docstring")).isTrue()
        assertThat(item.path("docstring").isNull).isTrue()
    }

    @Test
    fun `잘못된 요청은 같은 correlation id 의 ERROR 프레임을 받는다`(): Unit = runBlocking {
        val world = newWorld()
        macro(world, "attack_enemy", "Attack one enemy.")
        val cases = listOf(
            """{"query":"   ","limit":5}""" to "payload.query is required",
            """{"limit":5}""" to "payload.query is required",
            """{"query":"${"a".repeat(501)}","limit":5}""" to "at most 500 characters",
            """{"query":"enemy","limit":0}""" to "payload.limit must be between 1 and 10",
            """{"query":"enemy","limit":11}""" to "payload.limit must be between 1 and 10",
        )

        for ((payload, expected) in cases) {
            recorder.sent.clear()
            val requestId = UUID.randomUUID().toString()
            deliver(world, requestId, payload)

            val answer = recorder.sent.single()
            assertThat(answer.type).describedAs(payload).isEqualTo("ERROR")
            assertThat(answer.correlationId).isEqualTo(requestId)
            assertThat(answer.payload.toString()).contains(expected)
        }
    }

    @Test
    fun `500자 질의와 limit 10 은 받아들인다`(): Unit = runBlocking {
        val world = newWorld()

        search(world, "a".repeat(500), limit = 10)

        assertThat(recorder.sent.single().type).isEqualTo(MacroSearchFrames.SEARCH_RESULT)
    }

    @Test
    fun `content map 이 없는 런은 ERROR 프레임을 받는다`(): Unit = runBlocking {
        val world = newWorld()
        screenMacros.deleteAll()
        macros.deleteAll()
        screens.deleteAll()
        scenes.deleteAll()
        contentMaps.deleteAll()
        val requestId = UUID.randomUUID().toString()

        deliver(world, requestId, """{"query":"enemy","limit":5}""")

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.correlationId).isEqualTo(requestId)
        assertThat(answer.payload.toString()).contains("no content map")
    }

    @Test
    fun `새 macro 에 벡터를 채우고 docstring 이 바뀌면 다시 채운다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = macro(world, "cast_pair_at_enemy", "Cast two cards.", parameters = listOf("spell", "enemy"))

        val first = worker.runOnce()
        assertThat(first.succeeded).isEqualTo(1)
        assertThat(sourceTexts(macroId)).containsExactly("cast_pair_at_enemy(spell, enemy)\nCast two cards.")

        val untouched = worker.runOnce()
        assertThat(untouched.claimed).describedAs("바뀌지 않은 macro 는 다시 집지 않는다").isZero()
        assertThat(fake.embedCalls.get()).isEqualTo(1)

        macro(world, "cast_pair_at_enemy", "Cast the spell card and the element card.", parameters = listOf("spell", "enemy"))
        val second = worker.runOnce()
        assertThat(second.succeeded).describedAs("docstring 이 바뀌면 다시 임베딩한다").isEqualTo(1)
        assertThat(sourceTexts(macroId))
            .containsExactly("cast_pair_at_enemy(spell, enemy)\nCast the spell card and the element card.")

        assertThat(worker.runOnce().claimed).isZero()
        assertThat(fake.embedCalls.get()).isEqualTo(2)
    }

    @Test
    fun `parameter 이름이 바뀌어도 다시 채운다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = macro(world, "attack", "Attack.", parameters = listOf("enemy"))
        worker.runOnce()

        macro(world, "attack", "Attack.", parameters = listOf("enemy", "card"))
        worker.runOnce()

        assertThat(sourceTexts(macroId)).containsExactly("attack(enemy, card)\nAttack.")
    }

    @Test
    fun `임베딩 실패는 attempts 만 올리고 다음 tick 에 채운다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = macro(world, "attack", "Attack.")
        fake.failure = IllegalStateException("embed down")

        val failed = worker.runOnce()
        assertThat(failed.failed).isEqualTo(1)
        assertThat(sourceTexts(macroId)).isEmpty()

        fake.failure = null
        assertThat(worker.runOnce().succeeded).isEqualTo(1)
        assertThat(sourceTexts(macroId)).hasSize(1)
    }

    @Test
    fun `macro 를 지우면 벡터도 함께 지워진다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = macro(world, "attack", "Attack.")
        worker.runOnce()

        macros.deleteById(macroId)

        assertThat(embeddingRowCount(macroId)).isZero()
    }

    private suspend fun embeddingRowCount(macroId: Long): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM macro_embedding WHERE macro_id = :id")
            .bind("id", macroId)
            .map { row: Readable -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    /** 완성 행의 `source_text` 들. 대기 행(NULL)은 뺀다. */
    private suspend fun sourceTexts(macroId: Long): List<String> =
        databaseClient.sql(
            "SELECT source_text FROM macro_embedding WHERE macro_id = :id AND source_text IS NOT NULL"
        )
            .bind("id", macroId)
            .map { row: Readable -> row.get("source_text", String::class.java)!! }
            .flow()
            .toList()

    private fun names(answer: QaAgentEnvelope): List<String> =
        answer.payload.path("items").map { it.path("name").asText() }

    private fun matchedBy(answer: QaAgentEnvelope, index: Int): List<String> =
        answer.payload.path("items").path(index).path("matchedBy").map { it.asText() }

    /** 같은 이름이면 갱신이다(`upsertByName`). 등록 경로와 같은 SQL 을 쓴다. */
    private suspend fun macro(
        world: World,
        name: String,
        docstring: String?,
        parameters: List<String> = listOf("enemy"),
    ): Long {
        val definition = objectMapper.createObjectNode().put("name", name)
        definition.putObject("entry").put("name", name).putArray("parameters").apply {
            parameters.forEach { addObject().put("name", it).put("declared_type", "object") }
        }
        val names = objectMapper.createArrayNode().apply { parameters.forEach { add(it) } }
        return macros.upsertByName(
            contentMapId = world.contentMapId,
            name = name,
            source = "def $name(${parameters.joinToString(", ")}):\n    pass\n",
            definitionJson = Json.of(objectMapper.writeValueAsString(definition)),
            parameterNames = Json.of(objectMapper.writeValueAsString(names)),
            docstring = docstring,
        ).id
    }

    private suspend fun search(world: World, query: String, limit: Int = 5) {
        val payload = objectMapper.createObjectNode().put("query", query).put("limit", limit)
        deliver(world, UUID.randomUUID().toString(), objectMapper.writeValueAsString(payload))
    }

    private suspend fun deliver(world: World, messageId: String, payload: String) {
        router.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = MacroSearchFrames.SEARCH,
                qaTryId = world.qaTryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-10-13T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

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
