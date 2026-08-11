package kr.artel.orchestration.knowledge.dto

import java.time.Instant

/**
 * 프로젝트 지식창고를 그래프 한 장으로 읽기 위한 응답.
 *
 * Agent가 쓰는 [KnowledgeExpandResponse]와 목적이 다르다. 저쪽은 **한 항목에서 몇 홉**을 펼쳐
 * 런의 컨텍스트에 넣을 것을 고르는 도구이고, 이쪽은 사람이 **창고 전체의 생김새**를 보는 화면이다.
 * 그래서 이웃 예산도 홉 수도 없고, 대신 프로젝트 전체를 한 번에 담되 [nodeLimit]으로만 자른다.
 *
 * 운영 스코프(`scope_id IS NULL`)만 담는다. 실험 스코프의 지식은 그 arm 안에서만 의미가 있어
 * 창고 지도에 섞이면 실제 창고보다 커 보인다 — `knowledge_entry_facts` view가 실험 스코프를
 * 빼는 것과 같은 판단이다.
 *
 * @property truncated 노드가 [nodeLimit]을 넘어 잘렸는지. true면 **간선도 함께 잘린다** —
 *   잘려 나간 노드에 걸린 간선은 응답에 없다. 화면은 이 그래프를 "전체"라고 말하면 안 된다.
 */
data class KnowledgeGraphViewResponse(
    val projectId: String,
    val nodes: List<KnowledgeGraphNode>,
    val edges: List<KnowledgeGraphEdge>,
    val truncated: Boolean,
    val nodeLimit: Int
)

/**
 * 그래프 노드 하나 = 살아 있는 지식 항목 하나.
 *
 * 본문(`description`)은 싣지 않는다. 노드 수백 개의 본문을 한 번에 내리면 응답이 화면이 쓰는 양의
 * 몇 배가 되고, 화면은 어차피 요약만 그린다. 본문이 필요한 순간은 사용자가 노드 하나를 고른
 * 뒤이고 그때는 단건 조회가 있다.
 *
 * @property createdByQaTryId 이 항목을 만든 QA 런. 사람/문서 경로면 null이다. 화면이 "어느 런이
 *   만든 지식인가"로 색을 나누는 근거이며, 축별 지표(`knowledge_entry_facts`)와 같은 귀속이다.
 * @property version 현재 content 버전. 1보다 크면 고쳐진 항목이다.
 */
data class KnowledgeGraphNode(
    val id: String,
    val tag: String,
    val source: String,
    val summary: String,
    val version: Int,
    val createdByQaTryId: String?,
    val createdAt: Instant?
)

/**
 * 간선 하나.
 *
 * `relation`은 `LEADS_TO | REFINES | CONTRADICTS | DEPENDS_ON | REPLACES`이지만 문자열로 둔다 —
 * 값이 늘 때 이 DTO를 갈지 않아도 되고, 모르는 값을 만난 화면은 그리기만 하면 된다. 열거형으로
 * 좁히면 서버가 먼저 깨진다.
 *
 * @property note 이 간선이 왜 있는지, 주장한 런의 말로. `LEADS_TO`만은 "왜"가 아니라 **무엇을
 *   했는지**를 진다(V29 주석). 화면에서 간선을 고르면 이 문장이 근거로 읽힌다.
 */
data class KnowledgeGraphEdge(
    val from: String,
    val to: String,
    val relation: String,
    val note: String
)
