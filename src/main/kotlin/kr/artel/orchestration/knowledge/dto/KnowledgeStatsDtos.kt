package kr.artel.orchestration.knowledge.dto

import java.time.Instant

/**
 * 지식창고의 결과를 QA 런의 실행 설정 축으로 접은 집계(ARTEL-255 후속).
 *
 * [QaStatsResponse][kr.artel.orchestration.qa.dto.QaStatsResponse]와 같은 형태다 — 셀은
 * `(model, reasoningEffort, promptVersion, agentArch)` 4-튜플로 **분할**된 결과이고, 버전 하나는
 * 정확히 한 셀에 속한다. 그래서 단일 축 분해도 두 축 매트릭스도 클라이언트에서 부분합으로 나오며,
 * 축을 바꿀 때 서버를 다시 부르지 않는다.
 *
 * 세는 단위가 QA 쪽과 다르다는 것이 이 응답을 읽을 때 제일 먼저 알아야 할 사실이다. QA 집계의
 * 단위는 런이지만 여기는 **content 버전**이다 — 한 항목을 두 번 고치면 세 버전이고, 각 버전은
 * 그것을 만든 런의 축에 귀속된다.
 *
 * @property total 자르기 전 전체 합계. [truncated]일 때 [cells]의 합과 다르며, 이쪽이 실제 값이다.
 * @property truncated 조합 수가 [cellLimit]을 넘어 [cells]가 잘렸는지.
 */
data class KnowledgeStatsResponse(
    val projectId: String,
    val from: Instant,
    val to: Instant,
    val total: KnowledgeStatsTotals,
    val cells: List<KnowledgeRunConfigStatsCell>,
    val truncated: Boolean,
    val cellLimit: Int
)

/**
 * 축 하나 조합의 집계 한 줄.
 *
 * 축 값 4개는 모두 nullable이고 null은 "미상"이다. 다만 QA 집계와 달리 여기에는 **만든 런을
 * 아예 모르는 버전이 들어오지 않는다** — 그런 버전은 축에 귀속시킬 자리가 없어 집계 질의의
 * `qa_try` 조인이 떨군다. 화면의 미상 행은 "런은 아는데 그 런의 축이 비어 있다"는 뜻이다.
 */
data class KnowledgeRunConfigStatsCell(
    val model: String?,
    val reasoningEffort: String?,
    val promptVersion: String?,
    val agentArch: String?,
    val entryVersions: Long,
    val currentVersions: Long,
    val deletedVersions: Long,
    val repudiatedVersions: Long,
    val retrievalTotal: Long,
    val citationTotal: Long,
    val citationKnownTotal: Long
)

/**
 * [KnowledgeRunConfigStatsCell]에서 축만 뺀 전체 합계.
 *
 * @property entryVersions 이 축의 런들이 만든 content 버전 수. 항목 수가 아니다.
 * @property currentVersions 그중 아직 최신인 것. 나중 수정에 밀린 버전은 여기서 빠지지만
 *   폐기된 것은 아니다 — 두 값을 함께 봐야 "고쳐졌다"와 "버려졌다"가 갈린다.
 * @property deletedVersions 현재 삭제 상태인 것.
 * @property repudiatedVersions 삭제하되 **만든 런과 다른 런이** 지운 것. 후속 런이 공짜 심판
 *   노릇을 한 신호이고, 이 집계에서 제일 값어치 있는 숫자다.
 *
 *   ⚠️ 지금은 **수리와 폐기가 섞여 있다.** 항목을 지우고 다시 기록하는 경로가 열려 있어 수리
 *   한 번이 DELETE + CREATE로 나가면 여기 잡힌다. 둘을 가르려면 대체본이 원본을 가리키는
 *   관계가 쌓여야 한다(ARTEL-274). 화면은 이 값을 "수리 + 폐기의 합"으로 읽히게 표시해야 하며,
 *   그 사실을 숨기고 폐기율이라고만 쓰면 지식을 성실히 고치는 설정이 제일 나빠 보인다.
 * @property retrievalTotal 이 버전들이 검색으로 나간 총 횟수.
 * @property citationTotal 그중 실제로 인용된 횟수.
 * @property citationKnownTotal 인용 여부를 **알 수 있었던** 횟수. 인용 보고 기능이 붙기 전 런은
 *   여기에 들어오지 않는다. 그래서 인용률의 분모는 [retrievalTotal]이 아니라 이 값이어야 한다 —
 *   [retrievalTotal]로 나누면 기능이 없던 시절의 검색이 전부 "아무도 안 씀"으로 계산되어,
 *   지식창고가 실제보다 훨씬 쓸모없어 보인다.
 */
data class KnowledgeStatsTotals(
    val entryVersions: Long,
    val currentVersions: Long,
    val deletedVersions: Long,
    val repudiatedVersions: Long,
    val retrievalTotal: Long,
    val citationTotal: Long,
    val citationKnownTotal: Long
)
