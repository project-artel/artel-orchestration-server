package kr.artel.orchestration.knowledge

import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeStatsRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeStatsRow
import kr.artel.orchestration.knowledge.repository.KnowledgeUsageRepository
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeRetrieval
import kr.artel.orchestration.knowledge.service.KnowledgeSearchService
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `knowledge_entry_facts` view와 축별 롤업 검증(ARTEL-255).
 *
 * 이 파이프라인이 지탱하는 주장은 하나다: **후속 런이 공짜 심판이다.** 어떤 런이 만든 지식을
 * 나중 런이 지우면 그것이 부정 신호이고, 그 신호가 V25의 실행 설정 축으로 갈려야 모델·프롬프트·
 * 구조를 비교할 수 있다. 그래서 여기서 보는 것은 숫자가 맞는지가 아니라 **귀속이 맞는지**다.
 *
 * 셋을 못박는다.
 * 1. 지운 런이 아니라 **만든 런**의 축에 부정 신호가 붙는다.
 * 2. 자기가 만들고 자기가 지운 것은 폐기가 아니다 — 심판은 남이어야 성립한다.
 * 3. 이벤트 이력이 없는 기존 행은 view에는 남되(그 항목의 검색 사용량은 여전히 사실이다)
 *    축 롤업에서는 빠진다 — 축을 모르는 행이 축 통계를 오염시키면 안 된다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeStatsIntegrationTest {

    @Autowired private lateinit var knowledgeService: KnowledgeService
    @Autowired private lateinit var searchService: KnowledgeSearchService
    @Autowired private lateinit var statsRepository: KnowledgeStatsRepository
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var usageRepository: KnowledgeUsageRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var databaseClient: DatabaseClient

    private var projectId: Long = 0
    private var userId: Long = 0

    /** 두 축 조합. 이 둘이 갈리는지가 이 테스트의 전부다. */
    private var runA: Long = 0
    private var runB: Long = 0

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다.
     * 이력·사용 로그는 논리참조라 FK로 막히지 않지만, 남기면 다음 실행의 집계에 섞인다.
     */
    @AfterEach
    fun clean(): Unit = runBlocking {
        wipe()
    }

    /**
     * JUnit은 같은 클래스의 `@BeforeEach` 사이 순서를 보장하지 않으므로 정리와 준비를 한 곳에 둔다
     * (`KnowledgeSearchRouterIntegrationTest`와 같은 이유). 나누면 정리가 준비를 지우고 지나가
     * 모든 집계가 빈 결과가 된다.
     */
    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        val owner = signIn()
        userId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "knowledge-stats", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = userId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        runA = givenRun(model = "openai/gpt-a", promptVersion = "v1", arch = "single", effort = "high")
        runB = givenRun(model = "openai/gpt-b", promptVersion = "v2", arch = "multi", effort = "low")
    }

    private suspend fun wipe() {
        execute("DELETE FROM knowledge_event")
        usageRepository.deleteAll()
        knowledgeRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    /**
     * 이 테스트가 이 이슈의 핵심이다. A가 만든 것을 B가 지우면 **A의 축**에 폐기가 붙어야 한다.
     * B의 축에 붙으면 "많이 지우는 설정"이 나쁜 설정으로 읽혀 방향이 정반대가 된다.
     */
    @Test
    fun `다른 런이 지운 것은 만든 런의 축에 폐기로 붙는다`(): Unit = runBlocking {
        val victim = createEntry(runA, "A가 만든 지식")
        deleteEntry(runB, victim)

        val cells = aggregate().cells
        val a = cellOf(cells, "openai/gpt-a")
        assertThat(a.entryVersions).isEqualTo(1)
        assertThat(a.deletedVersions).isEqualTo(1)
        assertThat(a.repudiatedVersions)
            .describedAs("지운 런이 아니라 만든 런의 축에 붙어야 한다")
            .isEqualTo(1)
        assertThat(a.currentVersions).isEqualTo(1)

        // B는 아무것도 만들지 않았으므로 셀 자체가 없다 — 삭제는 B의 실적이 아니다.
        assertThat(cells.map { it.model }).doesNotContain("openai/gpt-b")
    }

    /** 자기가 만들고 자기가 지운 것은 스스로 고쳐 쓴 흔적이지 후속 런의 판정이 아니다. */
    @Test
    fun `자기가 만들고 자기가 지운 것은 폐기가 아니다`(): Unit = runBlocking {
        val own = createEntry(runB, "B가 만들고 B가 지운 지식")
        deleteEntry(runB, own)

        val b = cellOf(aggregate().cells, "openai/gpt-b")
        assertThat(b.deletedVersions).isEqualTo(1)
        assertThat(b.repudiatedVersions).isZero()
    }

    @Test
    fun `수정은 버전을 늘리고 검색 사용량은 버전별로 붙는다`(): Unit = runBlocking {
        val entry = createEntry(runA, "옛 요약")
        // 버전 1이 검색으로 두 번 나갔다.
        recordRetrieval(runB, entry, version = 1)
        recordRetrieval(runB, entry, version = 1)
        updateEntry(runB, entry, "새 요약")
        // 버전 2가 한 번.
        recordRetrieval(runB, entry, version = 2)

        val cells = aggregate().cells
        val a = cellOf(cells, "openai/gpt-a")
        val b = cellOf(cells, "openai/gpt-b")
        // 버전 1은 A가, 버전 2는 B가 만들었다. 한 항목이지만 두 축에 한 줄씩이다.
        assertThat(a.entryVersions).isEqualTo(1)
        assertThat(a.retrievalTotal).isEqualTo(2)
        assertThat(a.currentVersions).describedAs("버전 1은 더 이상 최신이 아니다").isZero()
        assertThat(b.entryVersions).isEqualTo(1)
        assertThat(b.retrievalTotal).isEqualTo(1)
        assertThat(b.currentVersions).isEqualTo(1)
    }

    /**
     * 인용 기능이 붙기 전에는 `citationTotal`이 0인데, 그것은 "아무도 인용하지 않았다"가 아니라
     * "아직 못 잰다"다. 둘을 가르는 것이 `citationKnownTotal`이고, 그래서 함께 낸다.
     */
    @Test
    fun `인용은 아직 못 재므로 known도 0이다`(): Unit = runBlocking {
        val entry = createEntry(runA, "지식")
        recordRetrieval(runB, entry, version = 1)

        val a = cellOf(aggregate().cells, "openai/gpt-a")
        assertThat(a.retrievalTotal).isEqualTo(1)
        assertThat(a.citationTotal).isZero()
        assertThat(a.citationKnownTotal)
            .describedAs("0이면 '인용 안 함'이 아니라 '못 잼'이다")
            .isZero()
    }

    /**
     * 이력이 없는 기존 행은 백필하지 않는다. 그렇다고 view에서 빼면 그 항목에 대한 검색 사용량이
     * 어디에도 안 잡혀 "검색된 적 없는 지식"으로 보인다. view에는 버전 1로 남되 만든 런은 모르는
     * 것으로 두고, 축 롤업은 `qa_try` 조인이 알아서 떨군다.
     */
    @Test
    fun `이력 없는 기존 행은 view에 남고 축 롤업에서는 빠진다`(): Unit = runBlocking {
        val legacy = knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "RULE",
                summary = "V26 이전에 만들어진 지식",
                description = "이벤트 이력이 없다"
            )
        ).id!!
        recordRetrieval(runB, legacy, version = 1)
        createEntry(runA, "이력 있는 지식")

        val facts = entryFacts()
        val legacyFact = facts.single { it.knowledgeId == legacy }
        assertThat(legacyFact.version).isEqualTo(1)
        assertThat(legacyFact.isCurrent).isTrue()
        assertThat(legacyFact.createdByQaTryId)
            .describedAs("모르는 것은 모르는 채로 둔다 — 지어내지 않는다")
            .isNull()
        assertThat(legacyFact.retrievalCount)
            .describedAs("view에서 빼면 이 사용량이 사라진다")
            .isEqualTo(1)

        val aggregate = aggregate()
        assertThat(aggregate.total!!.entryVersions)
            .describedAs("축을 모르는 행이 축 통계에 섞이면 안 된다")
            .isEqualTo(1)
        assertThat(aggregate.total!!.retrievalTotal).isZero()
    }

    /** 총계는 셀 자르기와 무관하게 전체에서 나온다. 잘린 셀의 합으로 총계를 내면 안 된다. */
    @Test
    fun `총계는 셀을 잘라도 전체를 센다`(): Unit = runBlocking {
        createEntry(runA, "A-1")
        createEntry(runB, "B-1")

        val aggregate = statsRepository.aggregateByRunConfig(
            projectId = projectId,
            userId = userId,
            from = Instant.now().minus(1, ChronoUnit.HOURS),
            to = Instant.now().plus(1, ChronoUnit.HOURS),
            limit = 1
        )
        assertThat(aggregate.cells).hasSize(1)
        assertThat(aggregate.truncated).isTrue()
        assertThat(aggregate.total!!.entryVersions).isEqualTo(2)
    }

    /** 참여자가 아니면 빈 집계다. 예외로 갈라 답하면 프로젝트의 존재 여부가 새어 나간다. */
    @Test
    fun `참여자가 아니면 빈 집계를 돌려준다`(): Unit = runBlocking {
        createEntry(runA, "지식")

        val outsider = signIn(seed = "outsider").userId.toLong()
        val aggregate = statsRepository.aggregateByRunConfig(
            projectId = projectId,
            userId = outsider,
            from = Instant.now().minus(1, ChronoUnit.HOURS),
            to = Instant.now().plus(1, ChronoUnit.HOURS),
            limit = 100
        )
        assertThat(aggregate.cells).isEmpty()
        assertThat(aggregate.total!!.entryVersions).isZero()
    }

    // --------------------------------------------------------------- helpers

    private suspend fun aggregate() = statsRepository.aggregateByRunConfig(
        projectId = projectId,
        userId = userId,
        from = Instant.now().minus(1, ChronoUnit.HOURS),
        to = Instant.now().plus(1, ChronoUnit.HOURS),
        limit = 100
    )

    private fun cellOf(cells: List<KnowledgeStatsRow>, model: String): KnowledgeStatsRow =
        cells.single { it.model == model }

    private suspend fun createEntry(qaTryId: Long, summary: String): Long =
        (knowledgeService.createFromQaTry(
            projectId, qaTryId,
            KnowledgeMutationRequest(tag = "RULE", summary = summary, description = "$summary 설명")
        ) as KnowledgeMutation.Applied).knowledgeId

    private suspend fun updateEntry(qaTryId: Long, knowledgeId: Long, summary: String) {
        knowledgeService.updateFromQaTry(
            projectId, qaTryId,
            KnowledgeMutationRequest(knowledgeId = knowledgeId.toString(), summary = summary)
        )
    }

    private suspend fun deleteEntry(qaTryId: Long, knowledgeId: Long) {
        knowledgeService.softDeleteFromQaTry(
            projectId, qaTryId, KnowledgeMutationRequest(knowledgeId = knowledgeId.toString())
        )
    }

    /** 벡터 검색을 태우지 않고 사용 로그만 남긴다 — 여기서 보는 것은 집계이지 검색이 아니다. */
    private suspend fun recordRetrieval(qaTryId: Long, knowledgeId: Long, version: Int) {
        searchService.recordRetrievals(
            qaTryId,
            listOf(KnowledgeRetrieval(knowledgeId = knowledgeId, version = version, rank = 1, score = 0.9))
        )
    }

    private suspend fun entryFacts(): List<EntryFact> =
        databaseClient.sql(
            """
            SELECT knowledge_id, version, is_current, created_by_qa_try_id,
                   deleted_by_qa_try_id, retrieval_count
              FROM knowledge_entry_facts
             WHERE project_id = :projectId
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .map { row: Readable ->
                EntryFact(
                    knowledgeId = row.get("knowledge_id", java.lang.Long::class.java)!!.toLong(),
                    version = row.get("version", java.lang.Integer::class.java)!!.toInt(),
                    isCurrent = row.get("is_current", java.lang.Boolean::class.java)!!.booleanValue(),
                    createdByQaTryId = row.get("created_by_qa_try_id", java.lang.Long::class.java)?.toLong(),
                    deletedByQaTryId = row.get("deleted_by_qa_try_id", java.lang.Long::class.java)?.toLong(),
                    retrievalCount = row.get("retrieval_count", java.lang.Long::class.java)!!.toLong()
                )
            }
            .flow()
            .toList()

    private data class EntryFact(
        val knowledgeId: Long,
        val version: Int,
        val isCurrent: Boolean,
        val createdByQaTryId: Long?,
        val deletedByQaTryId: Long?,
        val retrievalCount: Long
    )

    private suspend fun givenRun(
        model: String,
        promptVersion: String,
        arch: String,
        effort: String
    ): Long {
        val now = Instant.now()
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = projectId, payload = Json.of("{}"))
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "instance-${UUID.randomUUID().toString().take(8)}",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = userId,
                status = "RUNNING",
                model = model,
                promptVersion = promptVersion,
                agentArch = arch,
                reasoningEffort = effort,
                startedAt = now
            )
        )!!.id!!
    }

    private suspend fun execute(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitFirstOrNull()
    }

    private suspend fun signIn(seed: String = "stats"): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "$seed@example.com"
            )
        )!!
}
