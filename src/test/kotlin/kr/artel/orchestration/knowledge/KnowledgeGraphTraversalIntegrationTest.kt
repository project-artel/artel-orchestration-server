package kr.artel.orchestration.knowledge

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 지식 그래프 탐색 검증(ARTEL-275).
 *
 * 라우터를 태우지 않고 서비스에 직접 붙는다. 여기서 볼 것은 WS 계약이 아니라 **탐색 자체의
 * 성질**이고, 그것들은 QA 런 없이도 성립해야 한다.
 *
 * 스코프 케이스가 형식적 항목이 아니다. 그림자·툼스톤·스코프 edge가 곱해지는 자리라, 하나만
 * 어긋나도 실험이 운영 그래프를 깎거나 baseline의 관계가 스코프 런에서 통째로 사라진다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeGraphTraversalIntegrationTest {

    @Autowired private lateinit var graphService: KnowledgeGraphService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository

    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
    }

    @Test
    fun `깊이 1은 한 홉만 데려오고 깊이 2는 그 너머까지 간다`(): Unit = runBlocking {
        val a = knowledge("a")
        val b = knowledge("b")
        val c = knowledge("c")
        edge(a, b, "REFINES")
        edge(b, c, "REFINES")

        val oneHop = expand(listOf(a), depth = 1)
        assertThat(oneHop.neighbours.map { it.id.toLong() }).containsExactly(b)
        assertThat(oneHop.neighbours.single().depth).isEqualTo(1)

        val twoHop = expand(listOf(a), depth = 2)
        assertThat(twoHop.neighbours.map { it.id.toLong() }).containsExactly(b, c)
        assertThat(twoHop.neighbours.map { it.depth }).containsExactly(1, 2)
    }

    /** 방향이 반대인 edge도 나와야 한다 — 일반 규칙에 걸렸을 때 그것을 구체화하는 항목이 나와야 한다. */
    @Test
    fun `들어오는 방향의 관계도 이웃으로 나온다`(): Unit = runBlocking {
        val general = knowledge("일반")
        val specific = knowledge("구체")
        edge(specific, general, "REFINES")

        val neighbours = expand(listOf(general), depth = 1).neighbours
        assertThat(neighbours.single().id.toLong()).isEqualTo(specific)
        assertThat(neighbours.single().direction).isEqualTo("IN")
    }

    /** 모순이 판정을 바꾸는 유일한 신호라 fanout에 걸려도 살아남아야 한다. */
    @Test
    fun `fanout 상한에서 CONTRADICTS가 먼저 남는다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val refined = knowledge("refines")
        val depends = knowledge("depends")
        val contra = knowledge("contradicts")
        edge(seed, refined, "REFINES")
        edge(seed, depends, "DEPENDS_ON")
        edge(minOf(seed, contra), maxOf(seed, contra), "CONTRADICTS")

        val neighbours = expand(listOf(seed), depth = 1, fanout = 1).neighbours
        assertThat(neighbours).hasSize(1)
        assertThat(neighbours.single().id.toLong()).isEqualTo(contra)
        assertThat(neighbours.single().relation).isEqualTo("CONTRADICTS")
        // 대칭 관계에 방향을 실으면 렌더가 "…에 의해 모순됨" 같은 문장을 짓는다.
        assertThat(neighbours.single().direction).isEqualTo("NONE")
    }

    /**
     * seed 둘이 같은 이웃을 가리키면 한 줄만 나와야 한다. 리포지토리의 창 함수는
     * `(via, 이웃, relation)`까지만 접으므로 via가 다른 중복은 서비스가 접는다.
     */
    @Test
    fun `두 seed가 같은 이웃을 데려와도 한 줄만 나온다`(): Unit = runBlocking {
        val left = knowledge("left")
        val right = knowledge("right")
        val shared = knowledge("shared")
        edge(left, shared, "REFINES")
        edge(right, shared, "REFINES")

        val neighbours = expand(listOf(left, right), depth = 1).neighbours
        assertThat(neighbours.map { it.id.toLong() }).containsExactly(shared)
    }

    /**
     * 중복은 **예산을 재기 전에** 접혀야 한다. 뒤에 접으면 중복이 자리를 먹어 실제로 내보낼 수
     * 있는 것보다 적게 나가고, 아무것도 안 잘렸는데 `truncated`가 선다.
     */
    @Test
    fun `중복이 예산을 먹지 않는다`(): Unit = runBlocking {
        val left = knowledge("left")
        val right = knowledge("right")
        val shared = knowledge("shared")
        val onlyLeft = knowledge("only-left")
        edge(left, shared, "REFINES")
        edge(right, shared, "REFINES")
        edge(left, onlyLeft, "REFINES")

        // 서로 다른 이웃은 둘뿐이므로 예산 2면 전부 들어가고 잘린 것이 없다.
        val outcome = expand(listOf(left, right), depth = 1, nodeBudget = 2)
        assertThat(outcome.neighbours.map { it.id.toLong() }).containsExactlyInAnyOrder(shared, onlyLeft)
        assertThat(outcome.truncated).describedAs("접힌 중복은 잘린 것이 아니다").isFalse()
    }

    /**
     * 한 레벨에서 상한에 밀린 노드는 **내보낸 적이 없으므로** visited에 들어가면 안 된다.
     * 들어가면 다음 레벨에서 다른 경로로 닿아도 영영 안 나온다.
     */
    @Test
    fun `상한에 밀린 노드는 다음 레벨에서 다시 후보가 된다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val first = knowledge("first")
        val second = knowledge("second")
        edge(seed, first, "REFINES")
        edge(seed, second, "REFINES")
        edge(first, second, "REFINES")

        // fanout 1이라 레벨 1은 하나만 통과시킨다. 밀린 쪽이 레벨 2에서 나와야 한다.
        val outcome = expand(listOf(seed), depth = 2, fanout = 1, nodeBudget = 20)
        assertThat(outcome.neighbours.map { it.id.toLong() }).containsExactly(first, second)
        assertThat(outcome.neighbours.map { it.depth }).containsExactly(1, 2)
    }

    @Test
    fun `노드 예산을 넘기면 잘라내고 truncated를 세운다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        repeat(4) { edge(seed, knowledge("n$it"), "REFINES") }

        val outcome = expand(listOf(seed), depth = 1, fanout = 4, nodeBudget = 2)
        assertThat(outcome.neighbours).hasSize(2)
        assertThat(outcome.truncated).describedAs("잘린 것을 감추면 Agent는 이게 전부로 읽는다").isTrue()
    }

    /** `A REFINES B`, `B CONTRADICTS A`는 한 행을 양방향에서 읽으므로 visited가 없으면 핑퐁한다. */
    @Test
    fun `사이클이 있어도 끝난다`(): Unit = runBlocking {
        val a = knowledge("a")
        val b = knowledge("b")
        edge(a, b, "REFINES")
        edge(minOf(a, b), maxOf(a, b), "CONTRADICTS")

        val neighbours = expand(listOf(a), depth = 2).neighbours
        assertThat(neighbours.map { it.id.toLong() }).describedAs("seed가 자기 이웃으로 돌아오면 안 된다")
            .containsExactly(b)
    }

    @Test
    fun `소프트삭제된 이웃과 소프트삭제된 관계는 빠진다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val dead = knowledge("dead")
        val live = knowledge("live")
        val hidden = knowledge("hidden")
        edge(seed, dead, "REFINES")
        edge(seed, live, "REFINES")
        edge(seed, hidden, "REFINES", deletedAt = Instant.now())
        knowledgeRepository.save(knowledgeRepository.findById(dead)!!.copy(deletedAt = Instant.now()))

        val neighbours = expand(listOf(seed), depth = 1, fanout = 5).neighbours
        assertThat(neighbours.map { it.id.toLong() }).containsExactly(live)
    }

    @Test
    fun `다른 프로젝트의 관계는 보이지 않는다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val stranger = knowledge("stranger", projectId = OTHER_PROJECT)
        edgeRepository.save(
            KnowledgeEdgeEntity(
                projectId = OTHER_PROJECT,
                fromKnowledgeId = seed,
                toKnowledgeId = stranger,
                relation = "REFINES",
                note = "다른 프로젝트가 가진 관계"
            )
        )

        assertThat(expand(listOf(seed), depth = 1).neighbours).isEmpty()
    }

    /** "히트 1이 히트 3과 모순"은 이 기능이 말할 수 있는 가장 값진 것이라 따로 건진다. */
    @Test
    fun `seed끼리 걸린 관계는 edgesAmong으로 따로 나온다`(): Unit = runBlocking {
        val a = knowledge("a")
        val b = knowledge("b")
        edge(minOf(a, b), maxOf(a, b), "CONTRADICTS")

        // 둘 다 seed라 visited에 걸려 이웃으로는 안 나온다.
        assertThat(expand(listOf(a, b), depth = 1).neighbours).isEmpty()

        val among = graphService.edgesAmong(PROJECT, KnowledgeScope.PRODUCTION, listOf(a, b))
        assertThat(among).hasSize(1)
        assertThat(among.single().relation).isEqualTo("CONTRADICTS")
    }

    // ------------------------------------------------------------ 스코프 (ARTEL-256)

    /**
     * **이것이 정규 id 접기의 존재 이유다.** `VISIBLE`은 가려진 baseline을 빼기만 하고 그림자로
     * 갈아끼우지 않으므로, 접지 않으면 baseline에 걸린 관계가 스코프 런에서 통째로 사라진다.
     */
    @Test
    fun `스코프에서 그림자가 baseline을 대신해 이웃으로 나온다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val baseline = knowledge("옛 내용")
        edge(seed, baseline, "REFINES")
        val shadow = shadowOf(baseline, "스코프에서 고친 내용")

        val neighbours = expand(listOf(seed), depth = 1, scope = SCOPE).neighbours
        assertThat(neighbours).hasSize(1)
        assertThat(neighbours.single().id.toLong())
            .describedAs("baseline이 아니라 그 스코프에서 보이는 행이 나와야 한다").isEqualTo(shadow)
        assertThat(neighbours.single().summary).isEqualTo("스코프에서 고친 내용")

        // 운영 런에는 원본이 그대로 보인다.
        assertThat(expand(listOf(seed), depth = 1).neighbours.single().id.toLong()).isEqualTo(baseline)
    }

    @Test
    fun `스코프에서 툼스톤으로 지운 항목은 이웃으로 안 나온다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val baseline = knowledge("스코프가 지울 항목")
        edge(seed, baseline, "REFINES")
        // 툼스톤 = deleted_at이 찍힌 그림자.
        knowledgeRepository.save(
            knowledgeRepository.findById(baseline)!!.copy(
                id = null,
                scopeId = SCOPE.id,
                shadowsId = baseline,
                deletedAt = Instant.now(),
                createdAt = null,
                updatedAt = null
            )
        )

        assertThat(expand(listOf(seed), depth = 1, scope = SCOPE).neighbours).isEmpty()
        assertThat(expand(listOf(seed), depth = 1).neighbours).describedAs("운영에는 그대로 있다").hasSize(1)
    }

    /** 격리의 핵심: 실험 arm이 주장한 관계가 운영 그래프에 새면 되돌릴 방법이 없다. */
    @Test
    fun `스코프가 만든 관계는 운영 런에 보이지 않는다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val other = knowledge("other")
        edge(seed, other, "REFINES", scopeId = SCOPE.id)

        assertThat(expand(listOf(seed), depth = 1, scope = SCOPE).neighbours).hasSize(1)
        assertThat(expand(listOf(seed), depth = 1).neighbours).isEmpty()
    }

    @Test
    fun `툼스톤이 찍힌 baseline 관계는 그 스코프에서만 사라진다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val other = knowledge("other")
        val baselineEdge = edge(seed, other, "REFINES")
        edgeRepository.save(
            edgeRepository.findById(baselineEdge)!!.copy(
                id = null,
                scopeId = SCOPE.id,
                shadowsEdgeId = baselineEdge,
                deletedAt = Instant.now(),
                createdAt = null
            )
        )

        assertThat(expand(listOf(seed), depth = 1, scope = SCOPE).neighbours).isEmpty()
        assertThat(expand(listOf(seed), depth = 1).neighbours).hasSize(1)
    }

    /**
     * 스코프가 툼스톤을 안 찍고 같은 관계를 자기 스코프에 나란히 두면 baseline과 둘 다 통과한다.
     * 접지 않으면 같은 이웃이 두 줄로 나온다.
     */
    @Test
    fun `baseline과 스코프에 같은 관계가 있으면 한 줄만 나오고 스코프가 이긴다`(): Unit = runBlocking {
        val seed = knowledge("seed")
        val other = knowledge("other")
        edge(seed, other, "REFINES", note = "baseline이 적은 이유")
        edge(seed, other, "REFINES", scopeId = SCOPE.id, note = "스코프가 다시 적은 이유")

        val neighbours = expand(listOf(seed), depth = 1, scope = SCOPE).neighbours
        assertThat(neighbours).hasSize(1)
        assertThat(neighbours.single().note).isEqualTo("스코프가 다시 적은 이유")
    }

    // --- helpers ---

    private suspend fun expand(
        seeds: List<Long>,
        depth: Int,
        fanout: Int = 3,
        nodeBudget: Int = 20,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION
    ) = graphService.expand(
        projectId = PROJECT,
        scope = scope,
        seedIds = seeds,
        depth = depth,
        fanout = fanout,
        nodeBudget = nodeBudget,
        similar = null
    )

    private suspend fun knowledge(summary: String, projectId: Long = PROJECT): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "RULE",
                summary = summary,
                description = "설명"
            )
        ).id!!

    /** 스코프 런이 baseline을 고쳤을 때 생기는 그림자를 손으로 만든다(KnowledgeService와 같은 모양). */
    private suspend fun shadowOf(baselineId: Long, summary: String): Long =
        knowledgeRepository.save(
            knowledgeRepository.findById(baselineId)!!.copy(
                id = null,
                scopeId = SCOPE.id,
                shadowsId = baselineId,
                summary = summary,
                createdAt = null,
                updatedAt = null
            )
        ).id!!

    private suspend fun edge(
        from: Long,
        to: Long,
        relation: String,
        scopeId: Long? = null,
        note: String = "이유",
        deletedAt: Instant? = null
    ): Long = edgeRepository.save(
        KnowledgeEdgeEntity(
            projectId = PROJECT,
            scopeId = scopeId,
            fromKnowledgeId = from,
            toKnowledgeId = to,
            relation = relation,
            note = note,
            deletedAt = deletedAt
        )
    ).id!!

    private companion object {
        const val PROJECT = 4_100L
        const val OTHER_PROJECT = 4_200L
        val SCOPE = KnowledgeScope.of(7L)
    }
}
