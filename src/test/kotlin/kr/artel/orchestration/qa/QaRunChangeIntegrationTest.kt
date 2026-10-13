package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.macro.MacroDefinitionService
import kr.artel.orchestration.contentmap.macro.MacroRegisterRequest
import kr.artel.orchestration.contentmap.macro.MacroWrite
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroEventRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.NotRevertibleReason
import kr.artel.orchestration.qa.service.QaRunChangeKind
import kr.artel.orchestration.qa.service.QaRunChangeNotRevertibleException
import kr.artel.orchestration.qa.service.QaRunChangeService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant
import java.util.UUID

/**
 * QA run 이 knowledge 와 macro 에 한 변경을 보여 주고 revert 하는 경로(V100).
 *
 * 지키는 것 넷:
 * 1. **합치는 규칙** — 대상 하나는 한 줄이고, run 이전과 이후가 같으면 빠진다
 * 2. **나중 변경을 덮지 않는다** — run 뒤에 다른 event 가 있으면 409 `superseded`
 * 3. **revert 는 event 를 덧붙인다** — 되돌린 event 를 `reverts_event_id` 로 가리키고, 두 번째 revert 는
 *    409 `already_reverted`
 * 4. **등록이 macro 이력을 남긴다** — 정의가 바뀐 등록만 버전을 올리고 event 를 남긴다
 *
 * 표를 비우지 않는다. 자기 행만 새로 만들어 쓴다(`ProjectMacroHttpTest` 와 같은 이유, ARTEL-661).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaRunChangeIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var changeService: QaRunChangeService
    @Autowired private lateinit var knowledgeService: KnowledgeService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var knowledgeEvents: KnowledgeEventRepository
    @Autowired private lateinit var macroDefinitionService: MacroDefinitionService
    @Autowired private lateinit var macros: MacroRepository
    @Autowired private lateinit var macroEvents: MacroEventRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository

    // ------------------------------------------------------------------ 합치기

    @Test
    fun `같은 run 의 여러 try 가 만들고 고친 knowledge 는 CREATE 한 줄이다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)
        val id = createKnowledge(world, run.tries[0], summary = "슬라임은 불에 약하다")
        updateKnowledge(world, run.tries[1], id, summary = "슬라임은 불 피해를 두 배로 받는다")

        val item = changeService.list(run.id, world.userId).items.single()

        assertThat(item.kind).isEqualTo("KNOWLEDGE")
        assertThat(item.targetId).isEqualTo(id.toString())
        assertThat(item.change).isEqualTo("CREATE")
        assertThat(item.before).isNull()
        assertThat(item.after).isEqualTo("tag: RULE\nsummary: 슬라임은 불 피해를 두 배로 받는다\n\n본문")
        assertThat(item.name).isEqualTo("슬라임은 불 피해를 두 배로 받는다")
        assertThat(item.qaTryId).describedAs("마지막 event 를 남긴 try").isEqualTo(run.tries[1].toString())
        assertThat(item.decision).isEqualTo("APPLIED")
        assertThat(item.revertible).isTrue()
        assertThat(item.notRevertibleReason).isNull()
    }

    @Test
    fun `run 이전부터 있던 knowledge 를 두 번 고치면 before 는 run 이전 내용이다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val id = createKnowledge(world, earlier.tries[0], summary = "처음")
        val run = newRun(world)
        updateKnowledge(world, run.tries[0], id, summary = "둘째")
        updateKnowledge(world, run.tries[1], id, summary = "셋째")

        val item = changeService.list(run.id, world.userId).items.single()

        assertThat(item.change).isEqualTo("UPDATE")
        assertThat(item.before).isEqualTo("tag: RULE\nsummary: 처음\n\n본문")
        assertThat(item.after).isEqualTo("tag: RULE\nsummary: 셋째\n\n본문")
    }

    @Test
    fun `만들고 지운 것과 고쳤다가 되돌려 놓은 것은 빠진다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val kept = createKnowledge(world, earlier.tries[0], summary = "그대로")
        val run = newRun(world)
        val transient = createKnowledge(world, run.tries[0], summary = "잠깐")
        deleteKnowledge(world, run.tries[0], transient)
        updateKnowledge(world, run.tries[0], kept, summary = "바뀜")
        updateKnowledge(world, run.tries[1], kept, summary = "그대로")

        assertThat(changeService.list(run.id, world.userId).items).isEmpty()
    }

    @Test
    fun `항목은 changedAt 순서로 나오고 knowledge 와 macro 가 섞인다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)
        val first = createKnowledge(world, run.tries[0], summary = "먼저")
        val macroId = register(world, run.tries[0], "def open_menu(): pass")
        val last = createKnowledge(world, run.tries[1], summary = "나중")

        val items = changeService.list(run.id, world.userId).items

        assertThat(items.map { it.kind to it.targetId }).containsExactly(
            "KNOWLEDGE" to first.toString(),
            "MACRO" to macroId.toString(),
            "KNOWLEDGE" to last.toString(),
        )
        val macro = items[1]
        assertThat(macro.name).isEqualTo("open_menu")
        assertThat(macro.after).isEqualTo("def open_menu(): pass")
        assertThat(macro.change).isEqualTo("CREATE")
    }

    // ------------------------------------------------------------------ 나중 변경

    @Test
    fun `뒤에 다른 run 이 고친 항목은 SUPERSEDED 이고 revert 는 409 다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)
        val id = createKnowledge(world, run.tries[0], summary = "이 run")
        val later = newRun(world)
        updateKnowledge(world, later.tries[0], id, summary = "다음 run")

        val item = changeService.list(run.id, world.userId).items.single()
        assertThat(item.revertible).isFalse()
        assertThat(item.notRevertibleReason).isEqualTo("SUPERSEDED")
        assertThat(item.decision).isEqualTo("APPLIED")

        val error = postError(world, "/api/qa-runs/${run.id}/changes/knowledge/$id/revert")
        assertThat(error.first).isEqualTo(409)
        assertThat(error.second["code"].asText()).isEqualTo("superseded")
        assertThat(knowledgeRepository.findById(id)!!.summary).describedAs("나중 변경을 덮지 않는다").isEqualTo("다음 run")
    }

    // ------------------------------------------------------------------ knowledge revert

    @Test
    fun `knowledge CREATE 를 revert 하면 소프트삭제되고 두 번째 revert 는 409 다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)
        val id = createKnowledge(world, run.tries[0], summary = "잘못 배운 것")
        val createEvent = knowledgeEvents.findByKnowledgeIdOrderByIdAsc(id).toList().single()

        val item = post(world, "/api/qa-runs/${run.id}/changes/knowledge/$id/revert")

        assertThat(item["decision"].asText()).isEqualTo("REVERTED")
        assertThat(item["revertible"].asBoolean()).isFalse()
        assertThat(item["notRevertibleReason"].asText()).isEqualTo("ALREADY_REVERTED")
        assertThat(item["change"].asText()).describedAs("run 이 한 변경은 그대로 보인다").isEqualTo("CREATE")
        val row = knowledgeRepository.findById(id)!!
        assertThat(row.deletedAt).isNotNull()
        assertThat(row.deletedByQaTryId).describedAs("지운 것은 run 이 아니라 사람이다").isNull()
        val revertEvent = knowledgeEvents.findByKnowledgeIdOrderByIdAsc(id).toList().last()
        assertThat(revertEvent.event).isEqualTo("DELETE")
        assertThat(revertEvent.revertsEventId).isEqualTo(createEvent.id)
        assertThat(revertEvent.revertedByAppUserId).isEqualTo(world.userId)
        assertThat(revertEvent.qaTryId).isNull()

        val again = postError(world, "/api/qa-runs/${run.id}/changes/knowledge/$id/revert")
        assertThat(again.first).isEqualTo(409)
        assertThat(again.second["code"].asText()).isEqualTo("already_reverted")
    }

    @Test
    fun `knowledge UPDATE 를 revert 하면 run 이전 내용으로 돌아가고 버전이 오른다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val id = createKnowledge(world, earlier.tries[0], summary = "원래")
        val run = newRun(world)
        updateKnowledge(world, run.tries[0], id, summary = "고침")
        updateKnowledge(world, run.tries[1], id, summary = "또 고침")

        val item = changeService.revert(run.id, QaRunChangeKind.KNOWLEDGE, id, world.userId)

        assertThat(item.decision).isEqualTo("REVERTED")
        val row = knowledgeRepository.findById(id)!!
        assertThat(row.summary).isEqualTo("원래")
        assertThat(row.version).isEqualTo(4)
        assertThat(row.updatedByQaTryId).isNull()
        val events = knowledgeEvents.findByKnowledgeIdOrderByIdAsc(id).toList()
        assertThat(events.map { it.event }).containsExactly("CREATE", "UPDATE", "UPDATE", "UPDATE")
        assertThat(events.last().version).isEqualTo(4)
        assertThat(events.last().revertsEventId).isEqualTo(events[2].id)
    }

    @Test
    fun `knowledge DELETE 를 revert 하면 되살아나고 RESTORE event 가 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val id = createKnowledge(world, earlier.tries[0], summary = "지워질 것")
        val run = newRun(world)
        deleteKnowledge(world, run.tries[0], id)

        val listed = changeService.list(run.id, world.userId).items.single()
        assertThat(listed.change).isEqualTo("DELETE")
        assertThat(listed.after).isNull()
        assertThat(listed.before).isEqualTo("tag: RULE\nsummary: 지워질 것\n\n본문")

        changeService.revert(run.id, QaRunChangeKind.KNOWLEDGE, id, world.userId)

        val row = knowledgeRepository.findById(id)!!
        assertThat(row.deletedAt).isNull()
        assertThat(row.version).describedAs("복원은 버전을 올리지 않는다").isEqualTo(1)
        assertThat(knowledgeRepository.findVisibleById(id, world.projectId, null)).isNotNull()
        val last = knowledgeEvents.findByKnowledgeIdOrderByIdAsc(id).toList().last()
        assertThat(last.event).isEqualTo("RESTORE")
        assertThat(last.after).isNull()
    }

    /**
     * 스코프 run 이 baseline 을 고치면 그림자 행이 생긴다. 그 CREATE 를 revert 하면 그림자를 지울 뿐
     * 아니라 baseline 을 가리지 않게 해야 한다 — 그대로 두면 지운 그림자가 툼스톤이 되어 baseline 까지
     * 그 스코프에서 사라진다.
     */
    @Test
    fun `스코프 run 이 만든 그림자를 revert 하면 baseline 이 다시 보인다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val baseline = createKnowledge(world, earlier.tries[0], summary = "baseline")
        val run = newRun(world)
        val scope = KnowledgeScope.of(9_000_000 + world.projectId)
        val shadow = applied(
            knowledgeService.updateFromQaTry(
                world.projectId, scope, run.tries[0],
                KnowledgeMutationRequest(knowledgeId = baseline.toString(), summary = "그림자")
            )
        )
        assertThat(shadow).isNotEqualTo(baseline)

        changeService.revert(run.id, QaRunChangeKind.KNOWLEDGE, shadow, world.userId)

        assertThat(knowledgeRepository.findVisibleById(baseline, world.projectId, scope.id)?.summary)
            .isEqualTo("baseline")
        assertThat(knowledgeRepository.findById(shadow)!!.shadowsId).isNull()
    }

    @Test
    fun `run 이 바꾸지 않은 대상과 보이지 않는 run 은 404 다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val untouched = createKnowledge(world, earlier.tries[0], summary = "남의 것")
        val run = newRun(world)
        createKnowledge(world, run.tries[0], summary = "내 것")

        assertThat(postError(world, "/api/qa-runs/${run.id}/changes/knowledge/$untouched/revert").first)
            .isEqualTo(404)

        val stranger = newWorld()
        assertThatThrownBy { runBlocking { changeService.list(run.id, stranger.userId) } }
            .isInstanceOf(NotFoundException::class.java)
        assertThat(getStatus(stranger, "/api/qa-runs/${run.id}/changes")).isEqualTo(404)
    }

    // ------------------------------------------------------------------ macro

    @Test
    fun `등록은 정의가 바뀔 때만 버전을 올리고 event 를 남긴다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)

        val macroId = register(world, run.tries[0], "def open_menu(): pass")
        register(world, run.tries[0], "def open_menu(): pass")
        register(world, run.tries[1], "def open_menu():\n    pass")

        val events = macroEvents.findByMacroIdOrderByIdAsc(macroId).toList()
        assertThat(events.map { it.event to it.version }).containsExactly("CREATE" to 1, "UPDATE" to 2)
        assertThat(events.map { it.qaTryId }).containsExactly(run.tries[0], run.tries[1])
        assertThat(events.last().source).isEqualTo("def open_menu():\n    pass")
        assertThat(events.last().name).isEqualTo("open_menu")
        assertThat(macros.findById(macroId)!!.version).isEqualTo(2)
    }

    @Test
    fun `macro CREATE 를 revert 하면 행이 지워지고 DELETE event 가 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)
        val macroId = register(world, run.tries[0], "def open_menu(): pass")

        val item = post(world, "/api/qa-runs/${run.id}/changes/macro/$macroId/revert")

        assertThat(item["kind"].asText()).isEqualTo("MACRO")
        assertThat(item["decision"].asText()).isEqualTo("REVERTED")
        assertThat(macros.findById(macroId)).isNull()
        val last = macroEvents.findByMacroIdOrderByIdAsc(macroId).toList().last()
        assertThat(last.event).isEqualTo("DELETE")
        assertThat(last.source).isNull()
        assertThat(last.parameterNames).isNull()
        assertThat(last.name).isEqualTo("open_menu")
        assertThat(last.revertedByAppUserId).isEqualTo(world.userId)
    }

    @Test
    fun `macro UPDATE 를 revert 하면 run 이전 정의로 돌아간다`(): Unit = runBlocking {
        val world = newWorld()
        val earlier = newRun(world)
        val macroId = register(world, earlier.tries[0], "def open_menu(): pass")
        val run = newRun(world)
        register(world, run.tries[0], "def open_menu(): press('Esc')")

        val listed = changeService.list(run.id, world.userId).items.single()
        assertThat(listed.change).isEqualTo("UPDATE")
        assertThat(listed.before).isEqualTo("def open_menu(): pass")
        assertThat(listed.after).isEqualTo("def open_menu(): press('Esc')")

        val item = changeService.revert(run.id, QaRunChangeKind.MACRO, macroId, world.userId)

        assertThat(item.decision).isEqualTo("REVERTED")
        val row = macros.findById(macroId)!!
        assertThat(row.source).isEqualTo("def open_menu(): pass")
        assertThat(row.version).isEqualTo(3)
        val last = macroEvents.findByMacroIdOrderByIdAsc(macroId).toList().last()
        assertThat(last.event to last.version).isEqualTo("UPDATE" to 3)

        assertThatThrownBy { runBlocking { changeService.revert(run.id, QaRunChangeKind.MACRO, macroId, world.userId) } }
            .isInstanceOf(QaRunChangeNotRevertibleException::class.java)
            .extracting { (it as QaRunChangeNotRevertibleException).reason }
            .isEqualTo(NotRevertibleReason.ALREADY_REVERTED)
    }

    @Test
    fun `목록 응답의 key 가 계약과 같다`(): Unit = runBlocking {
        val world = newWorld()
        val run = newRun(world)
        createKnowledge(world, run.tries[0], summary = "계약")

        val body = get(world, "/api/qa-runs/${run.id}/changes")

        assertThat(body["items"].single().fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "kind", "targetId", "name", "change", "before", "after", "qaTryId", "changedAt",
            "decision", "revertible", "notRevertibleReason",
        )
        assertThat(body["items"].single()["before"].isNull).isTrue()
    }

    // ------------------------------------------------------------------ 픽스처

    private suspend fun createKnowledge(world: World, qaTryId: Long, summary: String): Long = applied(
        knowledgeService.createFromQaTry(
            world.projectId, KnowledgeScope.PRODUCTION, qaTryId,
            KnowledgeMutationRequest(tag = "RULE", summary = summary, description = "본문")
        )
    )

    private suspend fun updateKnowledge(world: World, qaTryId: Long, id: Long, summary: String) {
        applied(
            knowledgeService.updateFromQaTry(
                world.projectId, KnowledgeScope.PRODUCTION, qaTryId,
                KnowledgeMutationRequest(knowledgeId = id.toString(), summary = summary)
            )
        )
    }

    private suspend fun deleteKnowledge(world: World, qaTryId: Long, id: Long) {
        applied(
            knowledgeService.softDeleteFromQaTry(
                world.projectId, KnowledgeScope.PRODUCTION, qaTryId,
                KnowledgeMutationRequest(knowledgeId = id.toString())
            )
        )
    }

    private fun applied(mutation: KnowledgeMutation): Long =
        (mutation as? KnowledgeMutation.Applied)?.knowledgeId ?: error("knowledge 쓰기가 거절됨: $mutation")

    private suspend fun register(world: World, qaTryId: Long, source: String): Long {
        val result = macroDefinitionService.register(
            qaTries.findById(qaTryId)!!,
            MacroRegisterRequest(
                name = "open_menu",
                source = source,
                definition = objectMapper.readTree("""{"entrypoint":"open_menu","defs":[]}"""),
            )
        )
        return (result as? MacroWrite.Registered)?.macroId ?: error("macro 등록이 거절됨: $result")
    }

    private suspend fun newWorld(): World {
        val now = Instant.now()
        val user = signIn()
        val userId = user.userId.toLong()
        val project = projects.save(
            ProjectEntity(name = "run-changes-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now))
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        )
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
        )
        contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "run-changes",
            )
        )
        val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런"))!!.id!!
        return World(
            userId = userId,
            token = jwtService.issue(user),
            projectId = project.id!!,
            gameInstanceId = instance.id!!,
            testRunId = testRunId,
        )
    }

    /** try 둘짜리 run. 시나리오마다 try 하나다. */
    private suspend fun newRun(world: World): Run {
        val now = Instant.now()
        val run = qaRuns.save(
            QaRunEntity(
                testRunId = world.testRunId,
                gameInstanceId = world.gameInstanceId,
                startedBy = world.userId,
                status = "COMPLETED",
                startedAt = now,
                completedAt = now,
            )
        )!!
        val tries = (1..2).map {
            val scenario = testScenarios.save(TestScenarioEntity(projectId = world.projectId))!!
            qaTries.save(
                QaTryEntity(
                    testScenarioId = scenario.id!!,
                    gameInstanceId = world.gameInstanceId,
                    qaRunId = run.id,
                    startedBy = world.userId,
                    // 게임 인스턴스 하나에 활성 try 는 하나뿐이다(`uk_qa_try_active_instance`). 서비스는
                    // try 의 상태를 보지 않으므로 끝난 try 로 만든다.
                    status = "COMPLETED",
                    model = "claude-sonnet-4",
                    promptVersion = "v3",
                    runConfig = Json.of("{}"),
                    startedAt = now,
                    completedAt = now,
                )
            )!!.id!!
        }
        return Run(run.id!!, tries)
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

    private fun client() = WebClient.create("http://localhost:$port")

    private fun get(world: World, uri: String): JsonNode = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", world.token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(world: World, uri: String): JsonNode = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", world.token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun getStatus(world: World, uri: String): Int =
        try {
            get(world, uri)
            200
        } catch (e: WebClientResponseException) {
            e.statusCode.value()
        }

    /** 실패해야 하는 POST 의 상태 코드와 오류 body. */
    private fun postError(world: World, uri: String): Pair<Int, JsonNode> =
        try {
            post(world, uri)
            error("$uri 가 성공했다")
        } catch (e: WebClientResponseException) {
            e.statusCode.value() to objectMapper.readTree(e.responseBodyAsString)
        }

    private data class World(
        val userId: Long,
        val token: String,
        val projectId: Long,
        val gameInstanceId: Long,
        val testRunId: Long,
    )

    private data class Run(val id: Long, val tries: List<Long>)
}
