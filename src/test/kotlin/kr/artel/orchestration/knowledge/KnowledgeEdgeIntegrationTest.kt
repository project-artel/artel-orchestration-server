package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * knowledge 관계(edge)의 쓰기 경로 검증(ARTEL-274).
 *
 * 여기서 지키는 성질은 넷이다.
 *
 * 1. **범위가 런에서 나온다.** 프로젝트도 스코프도 payload가 아니라 `qa_try`에서 온다. 다른
 *    프로젝트의 항목을 끝점으로 지목해도 닿지 않는다.
 * 2. **거절이 throw가 아니다.** 잘못된 프레임은 ERROR 로그만 남기고 런은 RUNNING인 채여야 한다 —
 *    throw하면 receive 파이프라인이 끊겨 프레임 하나가 QA 런 전체를 실패시킨다.
 * 3. **끝점은 정규 id로 접힌다.** 스코프 런이 그림자 id로 링크해도 baseline id가 저장된다.
 *    이것이 깨지면 baseline 그래프와 스코프 그래프가 id 공간에서 갈라진다.
 * 4. **스코프 격리.** 스코프 런이 baseline edge를 거두면 원본은 한 행도 안 바뀌고 툼스톤만 생긴다.
 *    이것이 깨지면 실험이 운영 그래프를 깎는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeEdgeIntegrationTest {

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    /** 리액티브 트랜잭션은 테스트 롤백이 안 되므로 직접 비운다(다른 통합 테스트와 같은 이유). */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ link

    @Test
    fun `LINK 프레임은 관계를 이유와 함께 저장한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val general = givenKnowledge(run.projectId, "구매는 골드가 모자라면 막힌다")
        val specific = givenKnowledge(run.projectId, "상점에서만 골드 부족 안내가 뜬다")

        link(run.qaTryId, specific, general, "REFINES", "상점 화면에서 확인한 예외")

        val edge = edgeRepository.findAll().toList().single()
        assertThat(edge.fromKnowledgeId).isEqualTo(specific)
        assertThat(edge.toKnowledgeId).isEqualTo(general)
        assertThat(edge.relation).isEqualTo("REFINES")
        assertThat(edge.note).isEqualTo("상점 화면에서 확인한 예외")
        assertThat(edge.createdByQaTryId).isEqualTo(run.qaTryId)
        assertThat(edge.scopeId).describedAs("운영 런의 edge는 baseline이다").isNull()
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `LEADS_TO는 note에 무엇을 했는지를 싣고 왕복은 서로 다른 두 경로다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val town = givenKnowledge(run.projectId, "마을 화면")
        val shop = givenKnowledge(run.projectId, "상점 패널")

        link(run.qaTryId, town, shop, "LEADS_TO", "마을 상단바의 상점 버튼")
        link(run.qaTryId, shop, town, "LEADS_TO", "패널 우상단 X 또는 Escape")

        val edges = edgeRepository.findAll().toList()
        assertThat(edges).describedAs("방향이 다르면 다른 경로다").hasSize(2)
        assertThat(edges.map { it.fromKnowledgeId to it.toKnowledgeId })
            .containsExactlyInAnyOrder(town to shop, shop to town)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /**
     * 대칭 관계는 **한 행**이다. 두 방향으로 각각 쌓이면 `uq_knowledge_edge_live`가 둘을 같은
     * 주장으로 못 보고, 이웃도 두 줄로 나온다.
     */
    @Test
    fun `CONTRADICTS는 방향을 뒤집어 보내도 한 행이고 from이 작다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "포션은 즉시 회복한다")
        val b = givenKnowledge(run.projectId, "포션은 3초에 걸쳐 회복한다")

        link(run.qaTryId, b, a, "CONTRADICTS", "같은 포션에 대해 둘 다 참일 수 없다")
        link(run.qaTryId, a, b, "CONTRADICTS", "반대 방향으로 다시 주장")

        val edges = edgeRepository.findAll().toList()
        assertThat(edges).hasSize(1)
        assertThat(edges.single().fromKnowledgeId).isEqualTo(minOf(a, b))
        assertThat(edges.single().toKnowledgeId).isEqualTo(maxOf(a, b))
        // 두 번째는 중복으로 거절돼야 한다.
        assertThat(errorLogs(run.qaTryId)).hasSize(1)
        assertThat(errorLogs(run.qaTryId).single().message).contains("already linked")
    }

    @Test
    fun `같은 관계를 두 번 주장하면 거절한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")
        val b = givenKnowledge(run.projectId, "b")

        link(run.qaTryId, a, b, "REFINES", "처음")
        link(run.qaTryId, a, b, "REFINES", "두 번째")

        assertThat(edgeRepository.findAll().toList()).hasSize(1)
        assertThat(errorLogs(run.qaTryId).single().message).contains("already linked")
    }

    @Test
    fun `자기 자신에게 링크할 수 없다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")

        link(run.qaTryId, a, a, "REFINES", "말이 안 되는 관계")

        assertThat(edgeRepository.findAll().toList()).isEmpty()
        assertThat(errorLogs(run.qaTryId).single().message).contains("must differ")
    }

    /** 범위가 런에서 나오므로, 다른 프로젝트의 항목은 끝점이 될 수 없다. */
    @Test
    fun `다른 프로젝트의 항목은 끝점으로 지목해도 닿지 않는다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val mine = givenKnowledge(run.projectId, "내 지식")
        val theirs = givenKnowledge(seedRunningQaTry().projectId, "남의 지식")

        link(run.qaTryId, mine, theirs, "REFINES", "남의 프로젝트로 뻗는 관계")

        assertThat(edgeRepository.findAll().toList()).isEmpty()
        assertThat(errorLogs(run.qaTryId).single().message).contains("not found in project")
    }

    @Test
    fun `잘못된 프레임은 ERROR로 떨어지고 런은 계속된다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")
        val b = givenKnowledge(run.projectId, "b")

        link(run.qaTryId, a, b, "RELATED_TO", "없는 관계 종류")
        link(run.qaTryId, a, b, "REFINES", "   ")
        deliver(run.qaTryId, "KNOWLEDGE_LINK", """{"from_knowledge_id":"abc","to_knowledge_id":"$b","relation":"REFINES","note":"n"}""")

        assertThat(edgeRepository.findAll().toList()).isEmpty()
        assertThat(errorLogs(run.qaTryId)).hasSize(3)
        // 여기가 FAILED면 프레임 하나가 QA 런을 죽인 것이다.
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /**
     * 대체된 항목은 지워져 있는 것이 `REPLACES`의 뜻이다. 살아 있는 것만 허용하면 이 관계는
     * 영영 만들 수 없다. 다른 관계는 그 예외를 받지 않는다.
     */
    @Test
    fun `REPLACES만 지워진 항목을 to 끝점으로 받는다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val fresh = givenKnowledge(run.projectId, "새 규칙")
        val discarded = givenKnowledge(run.projectId, "옛 규칙")
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$discarded"}""")

        link(run.qaTryId, fresh, discarded, "REFINES", "지워진 것을 구체화할 수는 없다")
        assertThat(edgeRepository.findAll().toList()).isEmpty()

        link(run.qaTryId, fresh, discarded, "REPLACES", "이것이 저것을 대신한다")
        assertThat(edgeRepository.findAll().toList()).hasSize(1)
    }

    @Test
    fun `knowledge_mode가 learning이 아니면 링크도 거부된다`(): Unit = runBlocking {
        val frozen = seedRunningQaTry(knowledgeMode = "frozen")
        val a = givenKnowledge(frozen.projectId, "a")
        val b = givenKnowledge(frozen.projectId, "b")

        link(frozen.qaTryId, a, b, "REFINES", "얼린 런의 주장")

        assertThat(edgeRepository.findAll().toList()).isEmpty()
        assertThat(errorLogs(frozen.qaTryId).single().message).contains("knowledge_mode=frozen")
    }

    // -------------------------------------------------------- 정규 id (canonical)

    /**
     * 스코프 런이 baseline을 고치면 그 스코프의 검색은 **그림자의 id**를 낸다. 그 id로 링크해도
     * baseline id가 저장돼야 한다 — 아니면 실험이 끝난 뒤 그 edge가 무엇을 가리켰는지 못 읽는다.
     */
    @Test
    fun `그림자 id로 링크해도 baseline 정규 id로 저장된다`(): Unit = runBlocking {
        val scoped = seedRunningQaTry(knowledgeScopeId = SCOPE)
        val baseline = givenKnowledge(scoped.projectId, "옛 요약")
        val other = givenKnowledge(scoped.projectId, "다른 항목")
        deliver(scoped.qaTryId, "KNOWLEDGE_UPDATE", """{"knowledge_id":"$baseline","summary":"스코프에서 고친 요약"}""")
        val shadow = knowledgeRepository.findShadow(SCOPE, baseline)!!
        assertThat(shadow.id).describedAs("전제: 그림자가 생겼다").isNotEqualTo(baseline)

        link(scoped.qaTryId, shadow.id!!, other, "REFINES", "그림자를 지목한 링크")

        val edge = edgeRepository.findAll().toList().single()
        assertThat(edge.fromKnowledgeId).describedAs("그림자가 아니라 baseline이 저장돼야 한다").isEqualTo(baseline)
        assertThat(edge.scopeId).isEqualTo(SCOPE)
    }

    // ---------------------------------------------------------------- unlink

    @Test
    fun `운영 런의 UNLINK는 그 행에 표식을 남긴다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")
        val b = givenKnowledge(run.projectId, "b")
        link(run.qaTryId, a, b, "LEADS_TO", "버튼")

        unlink(run.qaTryId, a, b, "LEADS_TO")

        val edge = edgeRepository.findAll().toList().single()
        assertThat(edge.deletedAt).describedAs("행이 사라졌다면 하드 삭제다").isNotNull()
        assertThat(edge.deletedByQaTryId).isEqualTo(run.qaTryId)
        assertThat(edge.shadowsEdgeId).describedAs("운영 런은 툼스톤을 만들지 않는다").isNull()
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `거둔 관계는 다시 주장할 수 있다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")
        val b = givenKnowledge(run.projectId, "b")
        link(run.qaTryId, a, b, "LEADS_TO", "처음 본 경로")
        unlink(run.qaTryId, a, b, "LEADS_TO")

        link(run.qaTryId, a, b, "LEADS_TO", "다시 확인한 경로")

        val live = edgeRepository.findAll().toList().filter { it.deletedAt == null }
        assertThat(live).hasSize(1)
        assertThat(live.single().note).isEqualTo("다시 확인한 경로")
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /** 에이전트는 본 방향으로 부른다. 정규화하지 않으면 대칭 관계의 unlink가 아무것도 못 찾는다. */
    @Test
    fun `CONTRADICTS는 반대 방향으로 불러도 거둬진다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")
        val b = givenKnowledge(run.projectId, "b")
        link(run.qaTryId, minOf(a, b), maxOf(a, b), "CONTRADICTS", "모순")

        unlink(run.qaTryId, maxOf(a, b), minOf(a, b), "CONTRADICTS")

        assertThat(edgeRepository.findAll().toList().single().deletedAt).isNotNull()
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `없는 관계를 거두려 하면 거절한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val a = givenKnowledge(run.projectId, "a")
        val b = givenKnowledge(run.projectId, "b")

        unlink(run.qaTryId, a, b, "REFINES")

        assertThat(errorLogs(run.qaTryId).single().message).contains("is not linked")
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /**
     * **격리의 핵심 케이스.** 스코프 런이 baseline 관계를 거둬도 운영 그래프는 한 행도 바뀌면
     * 안 된다. 바뀌면 실험이 끝나도 되돌아오지 않는다.
     */
    @Test
    fun `스코프 런이 baseline 관계를 거두면 툼스톤만 생기고 원본은 그대로다`(): Unit = runBlocking {
        val production = seedRunningQaTry()
        val a = givenKnowledge(production.projectId, "마을")
        val b = givenKnowledge(production.projectId, "상점")
        link(production.qaTryId, a, b, "LEADS_TO", "상점 버튼")
        val baselineEdge = edgeRepository.findAll().toList().single()
        val scoped = seedRunningQaTry(projectId = production.projectId, knowledgeScopeId = SCOPE)

        unlink(scoped.qaTryId, a, b, "LEADS_TO")

        val edges = edgeRepository.findAll().toList()
        assertThat(edges).hasSize(2)
        val original = edges.single { it.id == baselineEdge.id }
        assertThat(original.deletedAt).describedAs("baseline은 한 행도 안 바뀐다").isNull()
        val tombstone = edges.single { it.id != baselineEdge.id }
        assertThat(tombstone.scopeId).isEqualTo(SCOPE)
        assertThat(tombstone.shadowsEdgeId).isEqualTo(baselineEdge.id)
        assertThat(tombstone.deletedAt).describedAs("툼스톤은 항상 죽어 있다").isNotNull()

        // 그 스코프에서는 사라지고, 운영 런에는 그대로 보인다.
        assertThat(visibleEdge(production.projectId, SCOPE, a, b, "LEADS_TO")).isNull()
        assertThat(visibleEdge(production.projectId, null, a, b, "LEADS_TO")).isNotNull()
    }

    @Test
    fun `스코프 런이 자기 관계를 거두면 툼스톤이 아니라 그 행을 지운다`(): Unit = runBlocking {
        val scoped = seedRunningQaTry(knowledgeScopeId = SCOPE)
        val a = givenKnowledge(scoped.projectId, "a")
        val b = givenKnowledge(scoped.projectId, "b")
        link(scoped.qaTryId, a, b, "REFINES", "스코프가 만든 관계")

        unlink(scoped.qaTryId, a, b, "REFINES")

        val edges = edgeRepository.findAll().toList()
        assertThat(edges).describedAs("자기 것을 가릴 이유가 없다").hasSize(1)
        assertThat(edges.single().shadowsEdgeId).isNull()
        assertThat(edges.single().deletedAt).isNotNull()
    }

    @Test
    fun `스코프가 만든 관계는 운영 런에 보이지 않는다`(): Unit = runBlocking {
        val production = seedRunningQaTry()
        val a = givenKnowledge(production.projectId, "a")
        val b = givenKnowledge(production.projectId, "b")
        val scoped = seedRunningQaTry(projectId = production.projectId, knowledgeScopeId = SCOPE)

        link(scoped.qaTryId, a, b, "REFINES", "실험 arm의 주장")

        assertThat(visibleEdge(production.projectId, SCOPE, a, b, "REFINES")).isNotNull()
        assertThat(visibleEdge(production.projectId, null, a, b, "REFINES"))
            .describedAs("실험이 운영 그래프에 새면 되돌릴 방법이 없다").isNull()
    }

    // --- helpers ---

    private suspend fun link(qaTryId: Long, from: Long, to: Long, relation: String, note: String) =
        deliver(
            qaTryId,
            "KNOWLEDGE_LINK",
            objectMapper.writeValueAsString(
                mapOf(
                    "from_knowledge_id" to from.toString(),
                    "to_knowledge_id" to to.toString(),
                    "relation" to relation,
                    "note" to note
                )
            )
        )

    private suspend fun unlink(qaTryId: Long, from: Long, to: Long, relation: String) =
        deliver(
            qaTryId,
            "KNOWLEDGE_UNLINK",
            objectMapper.writeValueAsString(
                mapOf(
                    "from_knowledge_id" to from.toString(),
                    "to_knowledge_id" to to.toString(),
                    "relation" to relation
                )
            )
        )

    private suspend fun visibleEdge(
        projectId: Long,
        scopeId: Long?,
        from: Long,
        to: Long,
        relation: String
    ): KnowledgeEdgeEntity? {
        val (a, b) = if (relation == "CONTRADICTS" && from > to) to to from else from to to
        return edgeRepository.findVisibleEdge(projectId, scopeId, a, b, relation)
    }

    private suspend fun deliver(qaTryId: Long, type: String, payload: String) {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = Instant.parse("2026-08-06T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun errorLogs(qaTryId: Long) =
        qaLogRepository.findAll().toList().filter { it.qaTryId == qaTryId && it.type == "ERROR" }

    private suspend fun givenKnowledge(projectId: Long, summary: String): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "RULE",
                summary = summary,
                description = "설명"
            )
        ).id!!

    /**
     * [projectId]를 주면 그 프로젝트에 런을 하나 더 만든다. 운영 런과 스코프 런이 **같은
     * 프로젝트**에 있어야 격리를 볼 수 있기 때문이다.
     */
    private suspend fun seedRunningQaTry(
        projectId: Long? = null,
        knowledgeScopeId: Long? = null,
        knowledgeMode: String? = null
    ): RunningQaTry {
        val owner = signIn(UUID.randomUUID().toString().take(8))
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val resolvedProjectId = projectId ?: projectRepository.save(
            ProjectEntity(name = "knowledge-edge", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = resolvedProjectId,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = resolvedProjectId, payload = Json.of("{}"))
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = resolvedProjectId,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "RUNNING",
                knowledgeScopeId = knowledgeScopeId,
                runConfig = knowledgeMode
                    ?.let { Json.of("""{"knowledge_mode":"$it"}""") }
                    ?: Json.of("{}"),
                startedAt = now
            )
        )!!
        return RunningQaTry(qaTryId = qaTry.id!!, projectId = resolvedProjectId)
    }

    private suspend fun signIn(seed: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "user-$seed@example.com"
            )
        )!!

    private data class RunningQaTry(val qaTryId: Long, val projectId: Long)

    private companion object {
        /** 실험 엔티티가 아직 없으므로 스코프 id는 임의의 값이면 된다(V28 주석 참조). */
        const val SCOPE = 7L
    }
}
