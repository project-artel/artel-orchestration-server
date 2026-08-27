package kr.artel.orchestration.knowledge

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.knowledge.dto.KnowledgeLinkRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeGraphMutation
import kr.artel.orchestration.knowledge.service.KnowledgeGraphService
import kr.artel.orchestration.knowledge.service.KnowledgeGraphViewService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 지식창고를 그래프 한 장으로 읽는 조회의 검증.
 *
 * 여기서 지키는 성질은 넷이다.
 *
 * 1. **간선은 살아남은 노드 사이만.** 잘려 나간 노드에 걸린 간선을 함께 내려보내면 화면이 없는
 *    노드를 지어내거나 선을 조용히 버리거나 둘 중 하나를 해야 한다. 둘 다 사실과 어긋난다.
 *    이것이 이 서비스의 핵심 불변식이라 노드와 간선을 각각 보는 것으로는 부족하다.
 * 2. **창고의 현재 모습만.** 지워진 항목은 노드가 아니다.
 * 3. **운영 스코프만.** 실험 스코프의 지식과 간선은 그 arm 안에서만 의미가 있어, 창고 지도에
 *    섞이면 실제 창고보다 커 보인다.
 * 4. **비참여자에게는 빈 그래프.** 예외로 갈라 답하면 프로젝트의 존재 여부가 새어 나간다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeGraphViewIntegrationTest {

    @Autowired private lateinit var viewService: KnowledgeGraphViewService
    @Autowired private lateinit var graphService: KnowledgeGraphService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private var projectId: Long = 0
    private var userId: Long = 0

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다
     * (`KnowledgeEdgeIntegrationTest`와 같은 이유). 정리와 준비를 한 메서드에 두는 것은 JUnit이
     * 같은 클래스의 `@BeforeEach` 사이 순서를 보장하지 않기 때문이다.
     */
    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        userId = signIn().userId.toLong()
        val now = Instant.now()
        projectId = projectRepository.save(
            ProjectEntity(name = "knowledge-graph-view", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = userId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
    }

    private suspend fun wipe() {
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `노드와 그 사이 간선이 함께 나온다`(): Unit = runBlocking {
        val general = givenKnowledge("구매는 골드가 모자라면 막힌다")
        val specific = givenKnowledge("상점에서만 골드 부족 안내가 뜬다")
        val lonely = givenKnowledge("아무와도 이어지지 않은 지식")
        link(specific, general, "REFINES", "상점 화면에서 확인한 예외")

        val response = viewService.graph(projectId, userId, nodeLimit = 200)

        assertThat(response.projectId).isEqualTo(projectId.toString())
        assertThat(response.nodeLimit).isEqualTo(200)
        assertThat(response.truncated).isFalse()
        assertThat(response.nodes.map { it.id })
            .containsExactlyInAnyOrder(general.toString(), specific.toString(), lonely.toString())

        val edge = response.edges.single()
        assertThat(edge.from).isEqualTo(specific.toString())
        assertThat(edge.to).isEqualTo(general.toString())
        assertThat(edge.relation).isEqualTo("REFINES")
        assertThat(edge.note).isEqualTo("상점 화면에서 확인한 예외")
    }

    /**
     * **이 테스트가 이 서비스의 핵심이다.** 노드가 잘리면 그 노드에 걸린 간선도 함께 빠져야 한다.
     * 남으면 화면은 응답에 없는 노드를 가리키는 선을 쥐게 된다.
     */
    @Test
    fun `잘려 나간 노드에 걸린 간선은 응답에 없다`(): Unit = runBlocking {
        val first = givenKnowledge("마을")
        val second = givenKnowledge("상점")
        val cut = givenKnowledge("한도 밖으로 밀려날 지식")
        link(first, second, "DEPENDS_ON", "상점은 마을에서만 열린다")
        link(first, cut, "DEPENDS_ON", "잘려 나간 쪽으로 뻗는 선행조건")

        val response = viewService.graph(projectId, userId, nodeLimit = 2)

        assertThat(response.nodes.map { it.id }).containsExactly(first.toString(), second.toString())
        val edge = response.edges.single()
        assertThat(edge.to)
            .describedAs("잘려 나간 노드를 가리키는 간선이 남으면 화면이 없는 노드를 지어내야 한다")
            .isEqualTo(second.toString())
        // 계약을 한 줄로: 간선의 양 끝은 언제나 응답에 실린 노드다.
        val nodeIds = response.nodes.map { it.id }
        assertThat(response.edges).allMatch { it.from in nodeIds && it.to in nodeIds }
    }

    @Test
    fun `삭제된 지식은 노드에 없다`(): Unit = runBlocking {
        val alive = givenKnowledge("살아 있는 지식")
        givenKnowledge("지워진 지식", deletedAt = Instant.now())

        val response = viewService.graph(projectId, userId, nodeLimit = 200)

        assertThat(response.nodes.map { it.id }).containsExactly(alive.toString())
        assertThat(response.truncated).describedAs("지워진 것은 잘린 것이 아니다").isFalse()
    }

    /**
     * 실험 스코프의 지식과 간선은 그 arm 안에서만 의미가 있다. 창고 지도에 섞이면 운영 창고가
     * 실제보다 커 보이고, 화면은 실험이 끝나면 사라질 항목을 창고의 일부로 그린다.
     */
    @Test
    fun `실험 스코프의 지식과 간선은 응답에 없다`(): Unit = runBlocking {
        val a = givenKnowledge("운영 지식 A")
        val b = givenKnowledge("운영 지식 B")
        givenKnowledge("실험 arm의 지식", scopeId = EXPERIMENT_SCOPE_ID)
        // 끝점은 운영 항목이지만 주장한 것은 실험 arm이다 — 그 주장은 운영 그래프에 없어야 한다.
        link(a, b, "REFINES", "실험 arm의 주장", scope = KnowledgeScope.of(EXPERIMENT_SCOPE_ID))

        val response = viewService.graph(projectId, userId, nodeLimit = 200)

        assertThat(response.nodes.map { it.id }).containsExactlyInAnyOrder(a.toString(), b.toString())
        assertThat(response.edges).isEmpty()
    }

    /** 예외로 갈라 답하면 프로젝트의 존재 여부가 샌다. 지식 지표 조회와 같은 판단이다. */
    @Test
    fun `비참여자는 예외가 아니라 빈 그래프를 받는다`(): Unit = runBlocking {
        val a = givenKnowledge("a")
        val b = givenKnowledge("b")
        link(a, b, "REFINES", "이유")
        val outsider = signIn(seed = "outsider").userId.toLong()

        val response = viewService.graph(projectId, outsider, nodeLimit = 200)

        assertThat(response.nodes).isEmpty()
        assertThat(response.edges).isEmpty()
        assertThat(response.truncated).describedAs("빈 그래프는 잘린 그래프가 아니다").isFalse()
        assertThat(response.nodeLimit).isEqualTo(200)
    }

    /**
     * 자를 때 **오래된 것부터** 남긴다. 그래프의 뼈대는 먼저 쌓인 항목들이 만들고, 최근 것만
     * 남기면 서로 연결되지 않은 파편이 흩어진 그림이 나온다.
     */
    @Test
    fun `nodeLimit을 넘기면 truncated가 서고 오래된 쪽이 남는다`(): Unit = runBlocking {
        val oldest = givenKnowledge("가장 먼저 쌓인 지식")
        val middle = givenKnowledge("그다음 지식")
        givenKnowledge("가장 최근 지식")

        val response = viewService.graph(projectId, userId, nodeLimit = 2)

        assertThat(response.nodes.map { it.id }).containsExactly(oldest.toString(), middle.toString())
        assertThat(response.truncated).isTrue()
        assertThat(response.nodeLimit).isEqualTo(2)
    }

    @Test
    fun `nodeLimit이 범위를 벗어나면 거절한다`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { viewService.graph(projectId, userId, nodeLimit = 0) }
        }.isInstanceOf(BadRequestException::class.java)

        assertThatThrownBy {
            runBlocking { viewService.graph(projectId, userId, nodeLimit = 501) }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /**
     * `createdByQaTryId`는 화면이 "어느 런이 만든 지식인가"로 노드 색을 나누는 근거다.
     * 사람/문서 경로의 `source_id`는 문서 id라, source를 함께 보지 않으면 그 값이 런으로 읽힌다.
     */
    @Test
    fun `만든 런은 QA 항목에만 실린다`(): Unit = runBlocking {
        val fromRun = givenKnowledge("런이 만든 지식", source = "QA", sourceId = 4_242L)
        val fromDoc = givenKnowledge("문서에서 뽑은 지식", source = "DOCS", sourceId = 77L)

        val nodes = viewService.graph(projectId, userId, nodeLimit = 200).nodes.associateBy { it.id }

        assertThat(nodes.getValue(fromRun.toString()).createdByQaTryId).isEqualTo("4242")
        assertThat(nodes.getValue(fromDoc.toString()).createdByQaTryId)
            .describedAs("문서 id를 런 id로 읽으면 화면이 없는 런을 가리킨다")
            .isNull()
        assertThat(nodes.getValue(fromRun.toString()).version).isEqualTo(1)
        assertThat(nodes.getValue(fromRun.toString()).createdAt).isNotNull()
    }

    /**
     * `LEADS_TO`는 쓰기만 얼렸다(ARTEL-594). 과거 런이 남긴 경로 간선은 이 지도에 그대로 그려져야
     * 한다 — 쓰기 경로가 그 값을 거절하므로 여기서는 행을 직접 심는다.
     */
    @Test
    fun `얼린 LEADS_TO 간선도 그래프 조회에 나온다`(): Unit = runBlocking {
        val town = givenKnowledge("마을")
        val shop = givenKnowledge("상점")
        edgeRepository.save(
            KnowledgeEdgeEntity(
                projectId = projectId,
                fromKnowledgeId = town,
                toKnowledgeId = shop,
                relation = "LEADS_TO",
                note = "마을 상단바의 상점 버튼"
            )
        )

        val response = viewService.graph(projectId, userId, nodeLimit = 200)

        val edge = response.edges.single()
        assertThat(edge.from).isEqualTo(town.toString())
        assertThat(edge.to).isEqualTo(shop.toString())
        assertThat(edge.relation).isEqualTo("LEADS_TO")
        assertThat(edge.note).isEqualTo("마을 상단바의 상점 버튼")
    }

    // --------------------------------------------------------------- helpers

    private suspend fun givenKnowledge(
        summary: String,
        scopeId: Long? = null,
        deletedAt: Instant? = null,
        source: String = "DOCS",
        sourceId: Long? = null
    ): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = source,
                sourceId = sourceId,
                tag = "RULE",
                summary = summary,
                description = "$summary 설명",
                scopeId = scopeId,
                deletedAt = deletedAt
            )
        ).id!!

    /**
     * 간선은 실제 쓰기 경로로 만든다 — 행을 직접 넣으면 정규화나 스코프 규칙이 빗나가도 이 테스트가
     * 눈치채지 못한다. 거절이 값이라 [KnowledgeGraphMutation.Applied]를 확인하지 않으면 픽스처가
     * 조용히 비어 있는 채로 테스트가 통과한다.
     *
     * `qaTryId`는 임의의 값이면 된다. 이 조회 경로는 `qa_try`를 조인하지 않고 knowledge_edge에
     * 하드 FK도 없다(V29).
     */
    private suspend fun link(
        from: Long,
        to: Long,
        relation: String,
        note: String,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION
    ) {
        val result = graphService.link(
            projectId,
            scope,
            LINKING_QA_TRY_ID,
            KnowledgeLinkRequest(
                fromKnowledgeId = from.toString(),
                toKnowledgeId = to.toString(),
                relation = relation,
                note = note
            )
        )
        assertThat(result).isInstanceOf(KnowledgeGraphMutation.Applied::class.java)
    }

    private suspend fun signIn(seed: String = "graph-view"): AuthenticatedUser =
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

    private companion object {
        /** 실험 엔티티가 아직 없으므로 스코프 id는 운영(NULL)이 아니기만 하면 된다(V28 주석 참조). */
        const val EXPERIMENT_SCOPE_ID = 9_001L

        /** 간선을 주장한 런. 이 조회는 런을 조인하지 않으므로 값 자체에 의미가 없다. */
        const val LINKING_QA_TRY_ID = 1L
    }
}
