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
 * @property anchors 이 항목이 묶인 씬·화면(ARTEL-605). **비어 있으면 게임 전체의 사실**이라는
 *   뜻이지 "앵커를 못 읽었다"가 아니다. 창고의 대부분이 그쪽이라 빈 배열이 정상 상태다.
 *   필드 자체는 언제나 있다 — 화면이 `undefined`와 `[]`를 갈라 다룰 이유가 없다.
 */
data class KnowledgeGraphNode(
    val id: String,
    val tag: String,
    val source: String,
    val summary: String,
    val version: Int,
    val createdByQaTryId: String?,
    val createdAt: Instant?,
    val anchors: List<KnowledgeGraphNodeAnchor>
)

/**
 * 노드 하나가 묶인 씬·화면 한 줄(ARTEL-605).
 *
 * ARTEL-591의 [KnowledgeAnchorView]와 같은 사실을 말하지만 **필드 이름이 camelCase다.** 저쪽은
 * Agent가 읽는 WS 프레임의 payload라 snake_case이고, 이쪽은 브라우저가 읽는 `/api` 응답이다.
 * 두 관례가 원래 다르므로 이 비대칭은 의도이며, 어느 한쪽을 다른 쪽에 맞추지 않는다.
 *
 * @property screenId 판정된 화면 id. **null이 정상이다** — 화면은 pulse 관측으로 판정되는 것이라
 *   (V40) 판정이 안 되는 순간이 있고, 그때 앵커는 씬까지만 말한다. 숫자가 아니라 문자열인 것은
 *   이 레포의 다른 id와 같은 이유다(FE 64비트 정밀도 손실 방지).
 */
data class KnowledgeGraphNodeAnchor(
    val sceneName: String,
    val screenId: String?
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
