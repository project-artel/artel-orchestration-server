package kr.artel.orchestration.knowledge.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphEdge
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphNode
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphViewResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service

/** 한 화면에 담을 노드 상한. 이보다 크면 그림이 읽히지 않으므로 응답보다 화면이 먼저 무너진다. */
private const val MAX_NODES = 500

@Service
class KnowledgeGraphViewService(
    private val knowledgeRepository: KnowledgeRepository,
    private val graphService: KnowledgeGraphService,
    private val accessService: ProjectAccessService
) {

    /**
     * 프로젝트의 운영 지식과 그 사이의 간선을 한 번에 읽는다.
     *
     * **비참여자에게는 빈 그래프를 준다.** 예외로 갈라 답하면 프로젝트의 존재 여부가 새어 나가고,
     * 그 판단은 QA·지식 집계와 같다.
     *
     * 간선은 **살아남은 노드 사이만** 담는다. 잘려 나간 노드나 삭제된 항목에 걸린 간선을 함께
     * 내려보내면 화면이 존재하지 않는 노드를 가리키는 선을 그리게 되고, 그때 화면은 없는 노드를
     * 지어내거나 선을 조용히 버리거나 둘 중 하나를 해야 한다. 둘 다 사실과 어긋난다.
     */
    suspend fun graph(projectId: Long, userId: Long, nodeLimit: Int): KnowledgeGraphViewResponse {
        if (nodeLimit !in 1..MAX_NODES) {
            throw BadRequestException("nodeLimit must be between 1 and $MAX_NODES")
        }
        if (!accessService.isMember(projectId, userId)) {
            return KnowledgeGraphViewResponse(
                projectId = projectId.toString(),
                nodes = emptyList(),
                edges = emptyList(),
                truncated = false,
                nodeLimit = nodeLimit
            )
        }

        // findVisible은 살아 있는 행만 돌려준다(deleted_at IS NULL). 지워진 지식은 창고의 현재
        // 모습이 아니므로 지도에 없는 것이 맞다 — 무엇이 지워졌는지는 지표 대시보드가 답한다.
        val all = knowledgeRepository
            .findVisible(projectId, KnowledgeScope.PRODUCTION.id, null, null)
            .toList()

        // 자를 때 오래된 것부터 남긴다. 그래프의 뼈대는 먼저 쌓인 항목들이 만들고, 최근 항목만
        // 남기면 서로 연결되지 않은 파편이 흩어진 그림이 나온다.
        val nodes = all.sortedBy { it.id }.take(nodeLimit)
        val ids = nodes.mapNotNull { it.id }

        val edges = graphService.edgesAmong(projectId, KnowledgeScope.PRODUCTION, ids)
            .map {
                KnowledgeGraphEdge(
                    from = it.fromKnowledgeId.toString(),
                    to = it.toKnowledgeId.toString(),
                    relation = it.relation,
                    note = it.note
                )
            }

        return KnowledgeGraphViewResponse(
            projectId = projectId.toString(),
            nodes = nodes.map { it.toNode() },
            edges = edges,
            truncated = all.size > nodes.size,
            nodeLimit = nodeLimit
        )
    }
}

/**
 * 만든 런은 `source=QA`일 때의 `source_id`다(V13). 사람/문서 경로면 그 값이 문서 id라 런으로
 * 읽으면 안 되고, 그래서 source를 함께 보고 가른다.
 */
private fun KnowledgeEntity.toNode() = KnowledgeGraphNode(
    id = requireNotNull(id).toString(),
    tag = tag,
    source = source,
    summary = summary,
    version = version,
    createdByQaTryId = sourceId?.takeIf { source == "QA" }?.toString(),
    createdAt = createdAt
)
