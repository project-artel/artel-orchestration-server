package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.knowledge.dto.KnowledgeLinkRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeUnlinkRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeRelation
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
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
 */
@Service
class KnowledgeGraphService(
    private val knowledgeRepository: KnowledgeRepository,
    private val edgeRepository: KnowledgeEdgeRepository,
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
     */
    suspend fun link(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeLinkRequest
    ): KnowledgeGraphMutation {
        val relation = KnowledgeRelation.fromWire(request.relation)
            ?: return rejected("relation must be one of ${KnowledgeRelation.NAMES}")
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
     */
    suspend fun unlink(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeUnlinkRequest
    ): KnowledgeGraphMutation {
        val relation = KnowledgeRelation.fromWire(request.relation)
            ?: return rejected("relation must be one of ${KnowledgeRelation.NAMES}")
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
