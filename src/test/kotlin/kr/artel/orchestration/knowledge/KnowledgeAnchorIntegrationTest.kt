package kr.artel.orchestration.knowledge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 지식을 씬·화면에 묶는 `anchor`(ARTEL-591)의 쓰기 경로 통합 테스트.
 *
 * 여기서 보는 것은 세 가지다.
 * 1. `KNOWLEDGE_CREATE`가 실은 `anchor` 가 실제로 저장되고, 지식과 **같은 트랜잭션**에서 만들어진다.
 * 2. `anchor` 를 싣지 않은 요청은 이 기능 이전과 완전히 같다 — `anchor` 가 없는 지식이 게임 전체의
 *    사실이고 그것이 기본값이다.
 * 3. 중복 `anchor` 를 DB가 막는다. `screen_id IS NULL` 쪽까지 막는 것이 요점이다 — Postgres는 UNIQUE에서
 *    NULL을 서로 다른 값으로 보므로 부분 유니크 인덱스가 없으면 그쪽만 무한히 쌓인다(V55).
 *
 * 읽기 쪽(검색 응답에 `anchor` 가 실리는지, `scene_name` 필터, 스코프 가시성)은
 * [KnowledgeVectorSearchIntegrationTest]가 맡는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeAnchorIntegrationTest {

    @Autowired private lateinit var knowledgeService: KnowledgeService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var anchorRepository: KnowledgeAnchorRepository

    companion object {
        /** 다른 knowledge 테스트(9000·30000번대)와 겹치지 않는 대역. */
        private val projectSeq = AtomicLong(40_000)
        private const val QA_TRY_ID = 8_100L
    }

    /**
     * `anchor` 는 knowledge를 FK 없이 논리참조하므로(V55) knowledge를 지워도 따라 사라지지 않는다.
     * 남기면 다음 테스트의 조회에 섞이므로 먼저 비운다.
     */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        anchorRepository.deleteAll()
        knowledgeRepository.deleteAll()
    }

    // --------------------------------------------------------------- 쓰기 경로

    @Test
    fun `anchor 를 실은 생성은 씬과 화면을 함께 남긴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        val created = create(
            projectId,
            request(summary = "전투 중 ESC는 아무것도 하지 않는다", sceneName = "Combat", screenId = "4242")
        )

        val knowledgeId = (created as KnowledgeMutation.Applied).knowledgeId
        val anchors = anchorsOf(knowledgeId)
        assertThat(anchors).hasSize(1)
        assertThat(anchors.single().sceneName).isEqualTo("Combat")
        assertThat(anchors.single().screenId).isEqualTo(4_242L)
    }

    /**
     * 화면은 pulse 관측으로 판정되는 것이라(V40) 판정이 안 되는 순간이 정상적으로 있다.
     * 그때 `anchor` 는 씬까지만 말하고 멈춰야지, 요청이 거절되어서는 안 된다.
     */
    @Test
    fun `화면을 모르면 씬까지만 묶는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        val created = create(projectId, request(sceneName = "Town"))

        val anchors = anchorsOf((created as KnowledgeMutation.Applied).knowledgeId)
        assertThat(anchors.single().sceneName).isEqualTo("Town")
        assertThat(anchors.single().screenId).isNull()
    }

    /**
     * 이 테스트가 "컬럼이 아니라 표"라는 결정의 근거다. 한 지식이 전투 화면 셋에 걸리는 것이
     * 정상이고, 컬럼이면 첫 화면만 남고 나머지는 소리 없이 사라진다.
     *
     * 두 번째 `anchor` 부터는 아직 쓰기 경로가 없다(`anchor` 수정 API는 ARTEL-591의 non-goal). 표가 그것을
     * 질 수 있다는 사실을 여기서 못박아 둔다 — 그 API가 생길 때 스키마를 다시 뜯지 않아도 된다.
     */
    @Test
    fun `한 지식이 여러 화면에 묶인다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val knowledgeId = created(projectId, request(sceneName = "Combat", screenId = "1"))

        anchorRepository.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = "Combat", screenId = 2L))
        anchorRepository.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = "Boss", screenId = 3L))

        assertThat(anchorsOf(knowledgeId).map { it.sceneName to it.screenId })
            .containsExactlyInAnyOrder("Combat" to 1L, "Combat" to 2L, "Boss" to 3L)
    }

    /**
     * 회귀 방어. `anchor` 를 싣지 않은 프레임은 이 기능 이전과 **완전히 같아야 한다** — `anchor` 가 없는
     * 지식이 게임 전체의 사실이고, 그것이 기본값이라 싸야 한다.
     */
    @Test
    fun `anchor 를 싣지 않은 생성은 지금까지와 같다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        val created = create(projectId, request(summary = "낙하 데미지는 5m부터"))

        val knowledgeId = (created as KnowledgeMutation.Applied).knowledgeId
        val row = knowledgeRepository.findById(knowledgeId)!!
        assertThat(row.summary).isEqualTo("낙하 데미지는 5m부터")
        assertThat(row.source).isEqualTo("QA")
        assertThat(anchorsOf(knowledgeId)).isEmpty()
    }

    /**
     * 화면은 씬 안에 산다(V55). 씬을 모르는 화면 `anchor` 를 조용히 저장하면 나중에 어느 씬의 화면이었는지
     * 되짚을 수 없어 그 `anchor` 가 영영 반쪽으로 남는다. **지식 자체도 저장되지 않아야 한다** — `anchor` 를
     * 실었는데 그것만 조용히 버려지면, Agent는 화면 지식을 적었다고 믿고 게임 전체 지식이 남는다.
     */
    @Test
    fun `씬 없이 화면만 실은 생성은 거절된다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        val result = create(projectId, request(screenId = "77"))

        assertThat(result).isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat((result as KnowledgeMutation.Rejected).reason).contains("scene_name")
        assertThat(knowledgeRepository.findVisible(projectId, null, null, null).toList()).isEmpty()
    }

    @Test
    fun `숫자가 아닌 화면 id는 거절된다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        val result = create(projectId, request(sceneName = "Town", screenId = "abc"))

        assertThat(result).isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat((result as KnowledgeMutation.Rejected).reason).contains("screen_id")
        assertThat(knowledgeRepository.findVisible(projectId, null, null, null).toList()).isEmpty()
    }

    // --------------------------------------------------------------- 중복 방지

    /**
     * **이 테스트가 V55의 부분 유니크 인덱스 두 벌이 있는 이유다.** 중복 `anchor` 는 조용히 틀린다 —
     * 검색 응답에 같은 화면이 두 번 실리고, 화면별 지식을 세는 질의가 같은 사실을 두 번 센다.
     */
    @Test
    fun `같은 화면에 같은 지식을 두 번 걸 수 없다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val knowledgeId = created(projectId, request(sceneName = "Combat", screenId = "9"))

        val error = runCatching {
            anchorRepository.save(
                KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = "Combat", screenId = 9L)
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(anchorsOf(knowledgeId)).hasSize(1)
    }

    /**
     * Postgres는 UNIQUE에서 NULL을 서로 다른 값으로 보므로 위 인덱스가 이쪽에는 걸리지 않는다.
     * V40의 `uk_screen_transition_auto`와 같은 처리로 짝을 이룬 부분 인덱스를 둔 이유이고,
     * content map이 채워지기 전까지 **모든** `anchor` 가 이쪽이라 여기가 실제로 일하는 쪽이다.
     */
    @Test
    fun `화면을 모르는 anchor 도 씬마다 하나뿐이다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val knowledgeId = created(projectId, request(sceneName = "Town"))

        val error = runCatching {
            anchorRepository.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = "Town"))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(anchorsOf(knowledgeId)).hasSize(1)
    }

    /** 같은 씬의 다른 화면은 별개의 `anchor` 다. 유일성이 지나치게 넓으면 그것도 잘못이다. */
    @Test
    fun `같은 씬의 다른 화면은 막지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val knowledgeId = created(projectId, request(sceneName = "Combat", screenId = "1"))

        anchorRepository.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = "Combat", screenId = 2L))
        // 화면을 모르는 `anchor` 도 화면을 아는 `anchor` 와 공존한다 — 서로 다른 사실이다.
        anchorRepository.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = "Combat"))

        assertThat(anchorsOf(knowledgeId)).hasSize(3)
    }

    // ---------------------------------------------------- 조회의 스코프 가시성

    /**
     * `anchor` 에는 자기 스코프가 없다 — knowledge 행이 스코프를 지고, 조회가 그 행을 조인해
     * `KnowledgeScopeSql.VISIBLE`을 지난다(V55). 그 술어가 빠지면 스코프에 가려졌어야 할 지식의
     * `anchor` 가 새는데, `anchor` 만 보면 그것이 어느 지식의 것인지 알 수 없어 새는 것을 알아채기도 어렵다.
     */
    @Test
    fun `가려진 지식의 anchor 는 조회되지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val scope = KnowledgeScope.of(6_101L)
        val other = KnowledgeScope.of(6_102L)

        val baseline = created(projectId, request(sceneName = "Town"))
        val inScope = anchored(seed(projectId, scope = scope), "Dungeon")
        // 이 그림자가 baseline을 가린다 — baseline의 `anchor` 도 함께 가려져야 한다.
        val shadow = anchored(seed(projectId, scope = scope, shadowsId = baseline), "Town")
        val deleted = anchored(seed(projectId, deletedAt = Instant.now()), "Graveyard")

        val ids = listOf(baseline, inScope, shadow, deleted)
        assertThat(visibleAnchors(ids, scope).map { it.sceneName })
            .containsExactlyInAnyOrder("Dungeon", "Town")
        assertThat(visibleAnchors(ids, scope).map { it.knowledgeId })
            .describedAs("가려진 baseline의 anchor 가 섞이면 안 된다")
            .containsExactlyInAnyOrder(inScope, shadow)
        // 다른 스코프에는 baseline만 보이고, 그래서 baseline의 `anchor` 만 보인다.
        assertThat(visibleAnchors(ids, other).map { it.knowledgeId }).containsExactly(baseline)
        // 소프트삭제된 지식의 `anchor` 는 어느 스코프에서도 나오지 않는다.
        assertThat(visibleAnchors(ids, KnowledgeScope.PRODUCTION).map { it.knowledgeId })
            .containsExactly(baseline)
    }

    // ------------------------------------------------------------------ 헬퍼

    private fun request(
        summary: String = "요약",
        sceneName: String? = null,
        screenId: String? = null
    ) = KnowledgeMutationRequest(
        tag = "RULE",
        summary = summary,
        description = "설명",
        sceneName = sceneName,
        screenId = screenId
    )

    private suspend fun create(
        projectId: Long,
        request: KnowledgeMutationRequest,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION
    ) = knowledgeService.createFromQaTry(projectId, scope, QA_TRY_ID, request)

    private suspend fun created(projectId: Long, request: KnowledgeMutationRequest): Long =
        (create(projectId, request) as KnowledgeMutation.Applied).knowledgeId

    /** 스코프 가시성 검증용 — 서비스를 거치지 않고 그림자·툼스톤 모양을 직접 심는다. */
    private suspend fun seed(
        projectId: Long,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION,
        shadowsId: Long? = null,
        deletedAt: Instant? = null
    ): Long = knowledgeRepository.save(
        KnowledgeEntity(
            projectId = projectId,
            scopeId = scope.id,
            shadowsId = shadowsId,
            deletedAt = deletedAt,
            source = "DOCS",
            tag = "RULE",
            summary = "요약",
            description = "설명"
        )
    ).id!!

    private suspend fun anchored(knowledgeId: Long, sceneName: String): Long {
        anchorRepository.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = sceneName))
        return knowledgeId
    }

    private suspend fun anchorsOf(knowledgeId: Long): List<KnowledgeAnchorEntity> =
        anchorRepository.findAll().toList().filter { it.knowledgeId == knowledgeId }

    private suspend fun visibleAnchors(knowledgeIds: List<Long>, scope: KnowledgeScope) =
        anchorRepository.findVisibleFor(knowledgeIds, scope.id).toList()
}
