package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.knowledge.dto.KnowledgeLinkRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeNeighbour
import kr.artel.orchestration.knowledge.dto.KnowledgeUnlinkRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeRelation
import kr.artel.orchestration.knowledge.entity.KnowledgeRetrievalKind
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeAmongRow
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeGraphTraversalRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeNeighbourRow
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeSimilarRow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 지식 그래프의 쓰기 경로(ARTEL-274). QA WebSocket의 `KNOWLEDGE_LINK` / `KNOWLEDGE_UNLINK`가
 * 이 서비스를 부른다.
 *
 * **거절은 예외가 아니라 값이다**([KnowledgeGraphMutation]). [KnowledgeService]와 같은 이유다 —
 * 호출자가 QA WS 라우터라, 거절을 예외로 알리면 receive 파이프라인이 끊겨 프레임 하나가 QA 런
 * 전체를 실패시킨다.
 *
 * ## 정규 id (canonical id)
 *
 * edge의 끝점은 **baseline id**로 저장한다. 스코프 런이 baseline을 고치면 그림자 행이 생기고
 * (ARTEL-256) 그 스코프의 검색·이웃은 그림자의 id를 내므로, 에이전트가 쥔 id가 그림자 id일 수
 * 있다. 그대로 저장하면 baseline 그래프와 스코프 그래프가 id 공간에서 갈라져 실험이 끝난 뒤
 * 그 edge가 무엇을 가리켰는지 아무도 못 읽는다. [canonicalIdOf]가 그 접기를 한다.
 *
 * ## 스코프
 *
 * [KnowledgeScope]를 **기본값 없이** 받는다. 빠뜨린 호출이 컴파일되지 않게 하려는 것이고,
 * 이유는 [KnowledgeService]의 같은 결정과 동일하다.
 *
 * 쓰기 규칙도 같다: 스코프 런이 만든 edge는 자기 스코프로 가고, baseline edge를 거둘 때는 원본을
 * 건드리지 않고 **툼스톤**을 만든다. 다만 knowledge와 달리 edge에는 "수정 그림자"가 없다 —
 * 고칠 수 있는 것이 `note`뿐이고 그것은 unlink 후 re-link로 되기 때문이다.
 *
 * ## 얼린 관계
 *
 * `LEADS_TO`는 이 서비스를 통과하지 못한다(ARTEL-594). link도 unlink도 거절이고, 이유는
 * [KnowledgeRelation]의 "얼린 값" 절에 있다. 읽기 경로([expand], [edgesAmong], 검색의 이웃,
 * 그래프 조회)는 그 값을 **아무것도 다르게 다루지 않는다** — 얼린 것은 어휘가 아니라 쓰기다.
 */
@Service
class KnowledgeGraphService(
    private val knowledgeRepository: KnowledgeRepository,
    private val edgeRepository: KnowledgeEdgeRepository,
    private val traversalRepository: KnowledgeGraphTraversalRepository,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(KnowledgeGraphService::class.java)

    /**
     * 항목 둘 사이에 관계를 하나 건다.
     *
     * 끝점은 둘 다 **이 프로젝트, 이 스코프에서 보이는** 항목이어야 한다. 프로젝트·스코프 격리의
     * 실체가 이 조회다 — 다른 프로젝트나 다른 스코프의 id를 넣으면 애초에 행이 없다.
     *
     * `REPLACES`의 `to` 끝점만 소프트삭제된 항목을 허용한다. 대체된 항목이 지워져 있는 것이 그
     * 관계의 뜻이므로, 살아 있는 것만 허용하면 REPLACES는 영영 만들 수 없다.
     *
     * 이미 있는 관계는 **거절한다**(중복 저장이 아니라). 같은 주장을 두 번 파일해 봐야 이웃이 두
     * 줄로 나올 뿐이고, 에이전트에게는 "이미 있다"가 곧 소득이다 — 그래프가 맞았다는 뜻이다.
     * 스코프 런이 baseline의 관계를 다른 `note`로 바꾸고 싶으면 unlink(툼스톤) 후 link이다.
     *
     * `LEADS_TO`는 **어떤 끝점으로도 만들 수 없다**(ARTEL-594). 화면 지도는 `content_map`이 지므로
     * 여기서는 읽기만 남았다 — 사유는 [KnowledgeRelation]의 "얼린 값" 절에 있다.
     */
    suspend fun link(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeLinkRequest
    ): KnowledgeGraphMutation {
        val relation = KnowledgeRelation.fromWire(request.relation)
            ?: return rejected("relation must be one of ${KnowledgeRelation.NAMES}")
        // 끝점을 찾기 전에 막는다. 얼린 값에 대해서는 끝점이 맞는지가 아무 의미가 없고, 거절 사유가
        // "그런 지식이 없다"로 바뀌면 에이전트가 id를 고쳐 다시 시도한다.
        if (relation == KnowledgeRelation.LEADS_TO) return rejected(LEADS_TO_LINK_FROZEN)
        val note = request.note?.trim()
        if (note.isNullOrEmpty()) return rejected("note must not be blank")

        val rawFrom = parseId(request.fromKnowledgeId)
            ?: return rejected("from_knowledge_id must be a numeric id")
        val rawTo = parseId(request.toKnowledgeId)
            ?: return rejected("to_knowledge_id must be a numeric id")

        // 정규화 **전에** 한 번 본다. 에이전트가 같은 항목을 그림자 id와 baseline id로 각각
        // 지목했다면 정규화 뒤에야 같아지므로, 아래에서 한 번 더 본다.
        if (rawFrom == rawTo) return rejected("from_knowledge_id and to_knowledge_id must differ")

        // `REPLACES`의 to만 지워진 항목을 허용한다. from은 언제나 살아 있어야 한다 — 지워진
        // 항목이 무언가를 대체한다는 것은 말이 안 된다.
        val from = findEndpoint(rawFrom, projectId, scope, allowDeleted = false)
            ?: return rejected("knowledge $rawFrom not found in project $projectId")
        val to = findEndpoint(rawTo, projectId, scope, allowDeleted = relation == KnowledgeRelation.REPLACES)
            ?: return rejected("knowledge $rawTo not found in project $projectId")

        val canonicalFrom = canonicalIdOf(from)
        val canonicalTo = canonicalIdOf(to)
        if (canonicalFrom == canonicalTo) {
            return rejected("from_knowledge_id and to_knowledge_id refer to the same knowledge entry")
        }

        val (storedFrom, storedTo) = relation.normalize(canonicalFrom, canonicalTo)

        // 유일 인덱스가 같은 것을 막지만, 먼저 걸러야 제약 위반이 예외가 아니라 값으로 처리된다.
        // (인스턴스 두 대가 같은 프레임을 동시에 처리하면 이 검사는 경합에 지고, 그때는 인덱스가
        //  잡아 저장이 실패한다 — 라우터가 그 예외를 ERROR 로그로 삼킨다.)
        edgeRepository.findVisibleEdge(projectId, scope.id, storedFrom, storedTo, relation.name)
            ?.let { return rejected("$storedFrom ${relation.name} $storedTo is already linked") }

        val saved = edgeRepository.save(
            KnowledgeEdgeEntity(
                projectId = projectId,
                scopeId = scope.id,
                fromKnowledgeId = storedFrom,
                toKnowledgeId = storedTo,
                relation = relation.name,
                note = note,
                createdByQaTryId = qaTryId
            )
        )
        logger.info(
            "knowledge 링크: edge={}, {} {} {}, project={}, scope={}, qaTry={}",
            saved.id, storedFrom, relation.name, storedTo, projectId, scope, qaTryId
        )
        return KnowledgeGraphMutation.Applied(requireNotNull(saved.id))
    }

    /**
     * 관계 하나를 거둔다.
     *
     * 대상은 id가 아니라 `(from, to, relation)`으로 찾는다 — 에이전트는 edge id를 본 적이 없다
     * ([KnowledgeUnlinkRequest] 참조). 끝점은 link과 **같은 규칙으로** 정규 id로 접는다.
     * 접지 않으면 그림자 id로 부른 unlink가 baseline edge를 못 찾는다.
     *
     * **스코프 런이 baseline edge를 거두면 원본은 그대로 두고 툼스톤을 만든다.** 직접 지우면
     * 운영 그래프가 실험 때문에 깎여나가고 실험이 끝나도 되돌아오지 않는다 —
     * [KnowledgeService.softDeleteFromQaTry]의 판단과 같다.
     *
     * `LEADS_TO`는 **거둘 수도 없다**(ARTEL-594). link만 막으면 서버는 언링크를 받는데 agent 도구는
     * 보내지 못해(ARTEL-590) 두 층이 서로 다른 규칙을 말하게 된다. 남은 경로 간선은 지도가 아니라
     * 과거 런의 기록으로 읽히므로 그대로 둔다 — [KnowledgeRelation]의 "얼린 값" 절 참조.
     */
    suspend fun unlink(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeUnlinkRequest
    ): KnowledgeGraphMutation {
        val relation = KnowledgeRelation.fromWire(request.relation)
            ?: return rejected("relation must be one of ${KnowledgeRelation.NAMES}")
        if (relation == KnowledgeRelation.LEADS_TO) return rejected(LEADS_TO_UNLINK_FROZEN)
        val rawFrom = parseId(request.fromKnowledgeId)
            ?: return rejected("from_knowledge_id must be a numeric id")
        val rawTo = parseId(request.toKnowledgeId)
            ?: return rejected("to_knowledge_id must be a numeric id")

        // 끝점 항목이 이미 지워졌어도 그것에 걸린 관계는 거둘 수 있어야 한다. 못 찾으면 준 id를
        // 그대로 쓴다 — 이미 정규 id이거나, 어차피 아래 조회에서 걸리지 않는다.
        val canonicalFrom = canonicalIdFor(rawFrom, projectId, scope)
        val canonicalTo = canonicalIdFor(rawTo, projectId, scope)
        val (storedFrom, storedTo) = relation.normalize(canonicalFrom, canonicalTo)

        val edge = edgeRepository.findVisibleEdge(projectId, scope.id, storedFrom, storedTo, relation.name)
            ?: return rejected("$storedFrom ${relation.name} $storedTo is not linked")

        val deletedAt = Instant.now(clock)

        // 스코프 런이 baseline을 거두는 경우에만 툼스톤이다. 운영 런이거나, 스코프 런이 자기
        // 스코프의 edge를 거두는 경우에는 그 행을 직접 지운다 — 이미 자기 것이다.
        // (KnowledgeService.shadowRequired와 같은 판단이다.)
        if (!scope.isProduction && edge.scopeId == null) {
            val baselineId = requireNotNull(edge.id)
            // 유일 인덱스가 막지만 먼저 걸러야 값으로 처리된다(link의 중복 검사와 같은 이유).
            // VISIBLE이 이미 툼스톤에 가려진 baseline을 뺐으므로 여기 걸리는 일은 경합뿐이다.
            edgeRepository.findTombstone(requireNotNull(scope.id), baselineId)
                ?.let { return rejected("$storedFrom ${relation.name} $storedTo is already unlinked in this run's knowledge scope") }
            val tombstone = edgeRepository.save(
                edge.copy(
                    id = null,
                    scopeId = scope.id,
                    shadowsEdgeId = baselineId,
                    deletedAt = deletedAt,
                    deletedByQaTryId = qaTryId,
                    // 이 스코프에 처음 생긴 행이므로 만든 시각도 물려받지 않는다.
                    createdAt = null,
                    // 이 런이 만든 행이라는 것은 사실이다. 가린 대상은 shadows_edge_id가 진다.
                    createdByQaTryId = qaTryId
                )
            )
            logger.info(
                "knowledge 스코프 링크 해제(툼스톤 생성): baseline={}, tombstone={}, project={}, scope={}, qaTry={}",
                baselineId, tombstone.id, projectId, scope, qaTryId
            )
            return KnowledgeGraphMutation.Applied(requireNotNull(tombstone.id))
        }

        edgeRepository.save(edge.copy(deletedAt = deletedAt, deletedByQaTryId = qaTryId))
        // 지우는 주체가 Agent라 삭제는 반드시 눈에 보여야 한다(knowledge 소프트삭제와 같은 이유).
        logger.info(
            "knowledge 링크 해제: edge={}, {} {} {}, project={}, scope={}, qaTry={}",
            edge.id, storedFrom, relation.name, storedTo, projectId, scope, qaTryId
        )
        return KnowledgeGraphMutation.Applied(requireNotNull(edge.id))
    }

    /**
     * [seedIds]에서 시작해 그래프를 [depth]홉 편다(ARTEL-275).
     *
     * **레벨당 질의 한 번**이고 노드당이 아니다. 재귀 CTE도 쓰지 않는다 — 깊이 상한이 2라
     * 반복 두 번이면 끝이고, 그 편이 상한과 예산을 코드에서 읽을 수 있게 남긴다.
     *
     * seed는 **Agent가 아는 id**(이 스코프에서 보이는 행)로 들어와 정규 id로 접힌다. 접지 않으면
     * 스코프 런에서 그림자 id로 시작한 확장이 baseline에 걸린 edge를 하나도 못 찾는다.
     *
     * `visited`는 seed를 포함해 이미 내보낸 노드 전부다. **사이클은 이 집합만으로 죽는다** —
     * `A REFINES B`, `B CONTRADICTS A`는 한 행을 양방향에서 읽으므로 없으면 핑퐁한다.
     *
     * 벡터 이웃은 seed가 **하나일 때만** 붙인다. 검색 자동 확장(seed가 여럿)에는 붙이지 않는데,
     * 히트를 낸 검색 자체가 벡터 검색이라 히트의 벡터 이웃은 `limit`을 올렸으면 나왔을 것에
     * 가깝기 때문이다 — 자동으로 붙이면 "limit 올리기"가 분장한 것이 되고 전사 비용만 두 배가 된다.
     *
     * [kind]는 **호출자가 준다**(ARTEL-293). 이 함수는 검색이 히트에 이웃을 밀어넣느라 부른
     * 것인지 에이전트가 `expand_knowledge`로 직접 부른 것인지 알 수 없고, 둘의 신호 세기는
     * 다르다 — 여기서 추측하면 그 구분이 조용히 틀린다.
     */
    suspend fun expand(
        projectId: Long,
        scope: KnowledgeScope,
        seedIds: List<Long>,
        depth: Int,
        fanout: Int,
        nodeBudget: Int,
        similar: SimilarSpec?,
        kind: KnowledgeRetrievalKind
    ): KnowledgeExpandOutcome {
        val seeds = seedIds.distinct().mapNotNull { id ->
            knowledgeRepository.findVisibleById(id, projectId, scope.id)?.let { id to canonicalIdOf(it) }
        }
        if (seeds.isEmpty()) return KnowledgeExpandOutcome(emptyList(), emptyList(), truncated = false)

        val canonicalBySeed = seeds.toMap()
        val visited = canonicalBySeed.values.toMutableSet()
        val neighbours = mutableListOf<NeighbourWithVersion>()
        var truncated = false

        var frontier = canonicalBySeed.values.toList()
        for (level in 1..depth) {
            if (frontier.isEmpty() || neighbours.size >= nodeBudget) break
            val rows = traversalRepository.neighboursOf(projectId, scope, frontier, visited, fanout)
            if (rows.isEmpty()) break

            // 같은 레벨에서 두 seed가 같은 이웃을 데려올 수 있다. 리포지토리의 창 함수는
            // (via, 이웃, relation)까지만 접으므로 **via가 다른 중복**은 여기까지 온다.
            // visited는 레벨 **사이**만 막지 레벨 **안**은 못 막는다.
            //
            // **예산을 재기 전에 접는다.** 뒤에 접으면 중복이 예산을 먹어 실제로 내보낼 수 있는
            // 것보다 적게 나가고, `truncated`가 아무것도 잘리지 않았는데도 선다.
            val distinct = rows.distinctBy { it.canonicalId }
            val room = nodeBudget - neighbours.size
            if (distinct.size > room) truncated = true
            val fresh = distinct.take(room)

            // visited에는 **실제로 내보낸 것만** 넣는다. 예산에 밀린 노드까지 넣으면 그 노드는
            // 다음 레벨에서도 제외되어, 자리가 남아도 영영 안 나온다.
            fresh.forEach { visited.add(it.canonicalId) }
            neighbours += fresh.map { it.toNeighbour(level) }
            frontier = fresh.map { it.canonicalId }
        }

        if (similar != null && seeds.size == 1) {
            val (seedId, seedCanonical) = seeds.single()
            val rows = traversalRepository.similarTo(
                projectId = projectId,
                scope = scope,
                knowledgeId = seedId,
                canonicalId = seedCanonical,
                kind = similar.kind,
                model = similar.model,
                excludeCanonicalIds = visited,
                maxDistance = similar.maxDistance,
                limit = similar.limit
            )
            neighbours += rows.map { it.toNeighbour(seedCanonical) }
        }

        return KnowledgeExpandOutcome(
            neighbours = neighbours,
            // 이웃도 런의 컨텍스트에 들어갔으므로 사용 기록에 남긴다(ARTEL-255). 로그를 우회하면
            // "이 지식이 쓸모 있었나"의 분모가 조용히 모자라고, 그 오류는 아무도 알려 주지 않는다.
            //
            // rank는 null이다 — 이웃은 관련도 순위를 매긴 결과가 아니라 그래프를 타고 온 것이다.
            // score도 벡터 이웃에만 있다. 둘 다 "모른다"가 null인 그 컬럼들의 관례 그대로다.
            retrievals = neighbours.map {
                KnowledgeRetrieval(
                    knowledgeId = it.id.toLong(),
                    version = it.version,
                    rank = null,
                    score = it.score,
                    kind = kind
                )
            },
            truncated = truncated
        )
    }

    /**
     * 이 항목들 **사이**에 걸린 관계(ARTEL-275).
     *
     * [expand]는 visited 집합 때문에 이런 edge를 뺀다. 그런데 "히트 1이 히트 3과 모순"은 이 기능이
     * 말할 수 있는 가장 값진 것이라 따로 건진다.
     */
    suspend fun edgesAmong(
        projectId: Long,
        scope: KnowledgeScope,
        knowledgeIds: List<Long>
    ): List<KnowledgeEdgeAmongRow> {
        val canonicals = knowledgeIds.distinct().mapNotNull { id ->
            knowledgeRepository.findVisibleById(id, projectId, scope.id)?.let(::canonicalIdOf)
        }
        return traversalRepository.edgesAmong(projectId, scope, canonicals)
    }

    /**
     * `정규 id → Agent가 아는 id` 표.
     *
     * 이웃은 `via`를 **정규 id**로 달고 돌아오는데, 검색 히트의 id는 이 스코프에서 보이는 행의
     * id다. 그림자가 낀 경우 둘이 달라, 이 표 없이는 이웃을 어느 히트 밑에 붙일지 알 수 없다.
     */
    suspend fun canonicalIndex(
        projectId: Long,
        scope: KnowledgeScope,
        knowledgeIds: List<Long>
    ): Map<Long, Long> =
        knowledgeIds.distinct().mapNotNull { id ->
            knowledgeRepository.findVisibleById(id, projectId, scope.id)?.let { canonicalIdOf(it) to id }
        }.toMap()

    private fun KnowledgeNeighbourRow.toNeighbour(depth: Int) = NeighbourWithVersion(
        id = knowledgeId.toString(),
        relation = relation,
        origin = ORIGIN_EDGE,
        // 대칭 관계에 방향을 실어 보내면 렌더가 "…에 의해 모순됨" 같은 문장을 짓게 된다.
        direction = if (KnowledgeRelation.fromWire(relation)?.symmetric == true) "NONE" else direction,
        note = note,
        tag = tag,
        source = source,
        summary = summary,
        depth = depth,
        score = null,
        via = viaCanonicalId.toString(),
        version = version
    )

    private fun KnowledgeSimilarRow.toNeighbour(viaCanonicalId: Long) = NeighbourWithVersion(
        id = knowledgeId.toString(),
        relation = RELATION_SIMILAR,
        origin = ORIGIN_VECTOR,
        direction = "NONE",
        // 아무도 주장한 적이 없다. 빈 문자열로 두면 "이유를 안 적은 관계"로 읽힌다.
        note = null,
        tag = tag,
        source = source,
        summary = summary,
        depth = 1,
        // pgvector의 `<=>`는 `1 - cosine_similarity`라 그대로 되돌린다(KnowledgeSearchService와 동일).
        score = 1.0 - distance,
        via = viaCanonicalId.toString(),
        version = version
    )

    /**
     * edge 끝점으로 쓸 항목을 이 프로젝트·스코프 안에서 찾는다.
     *
     * [allowDeleted]는 `REPLACES`의 `to` 끝점만을 위한 것이다 — 이유는
     * [KnowledgeRepository.findVisibleByIdIncludingDeleted] KDoc에 있다.
     */
    private suspend fun findEndpoint(
        id: Long,
        projectId: Long,
        scope: KnowledgeScope,
        allowDeleted: Boolean
    ): KnowledgeEntity? =
        if (allowDeleted) {
            knowledgeRepository.findVisibleByIdIncludingDeleted(id, projectId, scope.id)
        } else {
            knowledgeRepository.findVisibleById(id, projectId, scope.id)
        }

    /**
     * 이 행이 대표하는 baseline id. 그림자면 가리는 원본, 아니면 자기 자신이다.
     *
     * SQL의 `COALESCE(shadows_id, id)`와 같은 것이고, traversal 질의도 같은 식을 쓴다 —
     * 쓰기와 읽기가 다른 정의를 쓰면 저장한 edge를 조회가 못 찾는다.
     */
    private fun canonicalIdOf(entity: KnowledgeEntity): Long =
        entity.shadowsId ?: requireNotNull(entity.id)

    /**
     * [canonicalIdOf]와 같되 조회부터 한다. 못 찾으면 준 값을 그대로 쓴다.
     *
     * unlink 전용이다. 끝점 항목이 이미 사라졌어도 그 항목에 걸린 관계는 거둘 수 있어야 하고,
     * 그때 준 id는 대개 이미 정규 id다. 정말 엉뚱한 id였다면 아래 edge 조회에서 걸린다.
     */
    private suspend fun canonicalIdFor(id: Long, projectId: Long, scope: KnowledgeScope): Long =
        knowledgeRepository.findVisibleByIdIncludingDeleted(id, projectId, scope.id)
            ?.let(::canonicalIdOf)
            ?: id

    private fun parseId(raw: String?): Long? = raw?.trim()?.toLongOrNull()

    private fun rejected(reason: String) = KnowledgeGraphMutation.Rejected(reason)

    companion object {
        /**
         * 얼린 `LEADS_TO`에 대한 거절 사유(ARTEL-594).
         *
         * 조용한 무시가 아니라 **사유가 실린 거절**이어야 한다. 무시하면 에이전트는 저장됐다고
         * 믿고 다음 런이 그 경로를 찾아 헤맨다. 대체처를 문장에 박아 두는 이유도 같다 — 거절만
         * 받으면 도구가 이름을 바꿔 가며 재시도한다.
         */
        const val LEADS_TO_LINK_FROZEN =
            "LEADS_TO is read-only: screen routes are owned by content_map " +
                "(screen_transition, scene_edge), so no new LEADS_TO edge is accepted"

        /**
         * 언링크 쪽 사유. 링크와 문장을 나누는 것은 거절의 이유가 다르기 때문이다 — 링크는 "새로
         * 쓰지 않는다", 언링크는 "이미 있는 것을 기록으로 남긴다"이고, 후자를 링크 문구로 답하면
         * 왜 지울 수 없는지가 설명되지 않는다.
         */
        const val LEADS_TO_UNLINK_FROZEN =
            "LEADS_TO is read-only: screen routes are owned by content_map " +
                "(screen_transition, scene_edge), and existing LEADS_TO edges are kept " +
                "as a record of what past runs worked out"

        /** 런이 이유를 적어 주장한 관계. */
        const val ORIGIN_EDGE = "EDGE"

        /** 조회 시점에 계산한 유사도. 아무도 주장한 적이 없다. */
        const val ORIGIN_VECTOR = "VECTOR"

        /**
         * 벡터 이웃의 표시용 관계명.
         *
         * [KnowledgeRelation]에 **없다.** enum에 넣으면 CHECK를 통과해 저장될 수 있게 되는데,
         * 저장된 유사도는 임베딩 모델이 바뀌는 순간 조용히 거짓이 된다.
         */
        const val RELATION_SIMILAR = "SIMILAR"
    }
}

/**
 * 벡터 이웃을 붙일지와 어떻게 붙일지. null이면 안 붙인다.
 *
 * 모델을 여기까지 들고 오는 이유는 그것이 `artel.knowledge.backfill.model`에서 와야 하기
 * 때문이다 — 그래프 전용 설정을 만들면 읽기/쓰기 파티션이 갈라지고, 그 실패는 오류가 아니라
 * 조용한 빈 결과다([KnowledgeSearchProperties]의 KDoc과 같은 이유).
 */
data class SimilarSpec(
    val kind: String,
    val model: String,
    val maxDistance: Double,
    val limit: Int
)

/**
 * 확장 한 번의 결과. [KnowledgeSearchOutcome]의 선례대로 **Agent로 나갈 것과 남길 사실을 가른다**.
 *
 * [neighbours]가 DTO가 아니라 [NeighbourWithVersion]인 것이 그 가름이다. `version`은 사용 로그에만
 * 쓰이고 WS 계약에는 없어야 하는데, 한 타입에 합치면 그대로 Agent에게 나간다.
 */
data class KnowledgeExpandOutcome(
    val neighbours: List<NeighbourWithVersion>,
    val retrievals: List<KnowledgeRetrieval>,
    val truncated: Boolean
)

/**
 * 이웃 하나 + 기록용 `version`.
 *
 * @property version 내보낸 시점의 content 버전. 나중에 그 항목이 고쳐져도 이 런이 읽은 것은 이 값이다.
 */
data class NeighbourWithVersion(
    val id: String,
    val relation: String,
    val origin: String,
    val direction: String,
    val note: String?,
    val tag: String,
    val source: String,
    val summary: String,
    val depth: Int,
    val score: Double?,
    val via: String,
    val version: Int
) {
    fun toDto() = KnowledgeNeighbour(
        id = id,
        relation = relation,
        origin = origin,
        direction = direction,
        note = note,
        tag = tag,
        source = source,
        summary = summary,
        depth = depth,
        score = score,
        via = via
    )
}

/**
 * 그래프 쓰기 한 번의 결과(ARTEL-274).
 *
 * [KnowledgeMutation]을 재사용하지 않는 이유는 `Applied(knowledgeId)`가 edge id를 잘못 부르기
 * 때문이다. 정작 중요한 성질 — **거절이 값이지 예외가 아니라는 것** — 은 그대로 지킨다.
 */
sealed interface KnowledgeGraphMutation {
    /** @property edgeId 만들어졌거나 지워진 `knowledge_edge.id`. 툼스톤이면 툼스톤 행의 id다. */
    data class Applied(val edgeId: Long) : KnowledgeGraphMutation
    data class Rejected(val reason: String) : KnowledgeGraphMutation
}
