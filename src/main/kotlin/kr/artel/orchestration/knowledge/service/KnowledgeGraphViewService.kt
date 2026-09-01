package kr.artel.orchestration.knowledge.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphEdge
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphNode
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphNodeAnchor
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphViewResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service

/** 한 화면에 담을 노드 상한. 이보다 크면 그림이 읽히지 않으므로 응답보다 화면이 먼저 무너진다. */
private const val MAX_NODES = 500

@Service
class KnowledgeGraphViewService(
    private val knowledgeRepository: KnowledgeRepository,
    private val anchorRepository: KnowledgeAnchorRepository,
    private val graphService: KnowledgeGraphService,
    private val accessService: ProjectAccessService,
    private val platformAccessService: PlatformAccessService
) {

    /**
     * 프로젝트의 운영 지식과 그 사이의 간선을 한 번에 읽는다.
     *
     * **비참여자에게는 빈 그래프를 준다.** 예외로 갈라 답하면 프로젝트의 존재 여부가 새어 나가고,
     * 그 판단은 QA·지식 집계와 같다. `DEVELOPER` 등급은 참여하지 않아도 통과한다
     * ([PlatformAccessService]).
     *
     * 간선은 **살아남은 노드 사이만** 담는다. 잘려 나간 노드나 삭제된 항목에 걸린 간선을 함께
     * 내려보내면 화면이 존재하지 않는 노드를 가리키는 선을 그리게 되고, 그때 화면은 없는 노드를
     * 지어내거나 선을 조용히 버리거나 둘 중 하나를 해야 한다. 둘 다 사실과 어긋난다.
     */
    suspend fun graph(projectId: Long, userId: Long, nodeLimit: Int): KnowledgeGraphViewResponse {
        if (nodeLimit !in 1..MAX_NODES) {
            throw BadRequestException("nodeLimit must be between 1 and $MAX_NODES")
        }
        if (!accessService.isMember(projectId, userId) &&
            !platformAccessService.seesAllProjects(userId)
        ) {
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

        val anchors = anchorsFor(ids)

        return KnowledgeGraphViewResponse(
            projectId = projectId.toString(),
            nodes = nodes.map { it.toNode(anchors) },
            edges = edges,
            truncated = all.size > nodes.size,
            nodeLimit = nodeLimit
        )
    }

    /**
     * 응답에 실릴 노드 **전체**의 앵커를 한 번에 데려와 지식 id로 묶는다(ARTEL-605).
     *
     * **노드마다 부르지 않는다.** 이 조회는 노드를 최대 [MAX_NODES]개까지 내므로 노드당 한 질의는
     * 응답 하나를 수백 질의로 만든다. 그 비용은 앵커가 하나도 없는 프로젝트에서도 그대로 나서,
     * 앵커를 쓰지 않는 팀이 앵커 기능의 값을 치른다.
     *
     * **스코프 술어는 리포지토리가 진다.** 노드는 이미 운영 스코프로 걸러진 뒤라 군더더기로
     * 보이지만, 앵커 조회가 자기 힘으로 [kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql]
     * 를 지나지 않으면 이 리포지토리를 부르는 다음 자리에서 가려진 지식의 앵커가 샌다. 앵커만
     * 보아서는 그것이 어느 지식의 것인지 알 수 없어 새는 것을 알아채기도 어렵다.
     */
    private suspend fun anchorsFor(ids: List<Long>): Map<Long, List<KnowledgeGraphNodeAnchor>> {
        // 빈 컬렉션을 넘기면 `IN ()` 이 되어 SQL 이 깨진다(KnowledgeAnchorRepository 주석).
        if (ids.isEmpty()) return emptyMap()
        return anchorRepository.findVisibleFor(ids, KnowledgeScope.PRODUCTION.id)
            .toList()
            .groupBy(KnowledgeAnchorEntity::knowledgeId) { it.toNodeAnchor() }
    }
}

/**
 * 만든 런은 `source=QA`일 때의 `source_id`다(V13). 사람/문서 경로면 그 값이 문서 id라 런으로
 * 읽으면 안 되고, 그래서 source를 함께 보고 가른다.
 */
private fun KnowledgeEntity.toNode(
    anchors: Map<Long, List<KnowledgeGraphNodeAnchor>>
): KnowledgeGraphNode {
    val knowledgeId = requireNotNull(id)
    return KnowledgeGraphNode(
        id = knowledgeId.toString(),
        tag = tag,
        source = source,
        summary = summary,
        version = version,
        createdByQaTryId = sourceId?.takeIf { source == "QA" }?.toString(),
        createdAt = createdAt,
        // 앵커가 없는 지식은 게임 전체의 사실이다. 그것이 기본값이라 빈 배열이 정상이다.
        anchors = anchors[knowledgeId].orEmpty()
    )
}

/** 앵커 행을 브라우저 계약 모양으로. id 계열은 이 레포의 다른 응답과 같이 문자열로 낸다. */
private fun KnowledgeAnchorEntity.toNodeAnchor() =
    KnowledgeGraphNodeAnchor(sceneName = sceneName, screenId = screenId?.toString())
