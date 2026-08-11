package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 저작 Agent가 세션 내내 들고 있는 TestCase 목록의 한 줄(ARTEL-318).
 *
 * **본문(precondition/expected)까지 담는다.** 목록의 일이 "무엇이 존재하는가"를 알리는 데서 끝나지
 * 않기 때문이다 — Agent는 고른 케이스로 실제 스텝을 써야 하고, 그러려면 사전조건과 기대결과를
 * 읽어야 한다. 본문을 빼면 고른 뒤에 다시 가져오는 경로가 필요한데, 그러면 왕복이 생기고 그 왕복이
 * 몇 건까지 되는지가 다시 상한이 된다. 애초에 이 작업의 출발점이 "전량을 실어도 컨텍스트가 감당
 * 된다"는 측정이었으므로, 그 예산을 안 쓰고 왕복을 만드는 것은 앞뒤가 맞지 않는다.
 *
 * 실측(한국어 케이스 8건, o200k 기준 1000건 환산): 본문 없이 19.9k, 본문 포함 74.4k. 이 목록은
 * 프롬프트 앞 고정 블록에 실려 캐시를 타므로 2턴째부터의 실비용은 그보다 훨씬 작다.
 *
 * 와이어 필드명이 snake_case인 것은 Agent 계약을 따르기 때문이다([AgentScenario]와 같은 규칙).
 * [id]가 문자열이 아니라 숫자인 것도 같은 이유다 — Agent가 이 값을 그대로 스텝의 `case_id`로 돌려주고,
 * 그쪽 계약이 숫자다. (FE 응답인 [TestCaseResponse]가 id를 문자열로 내는 것과 다르다. 그쪽은 JS의
 * 64비트 정밀도 때문이고, 여기서는 Agent 계약이 먼저다.)
 *
 * DB 조회 projection으로도 이 클래스를 그대로 쓴다 — 한 줄에 클래스를 둘로 두면 같은 이름을 두 곳에
 * 적게 되고, 한쪽만 고쳐지는 순간 조용히 어긋난다.
 *
 * @property verificationStatus 케이스 자체가 유효한지(DRAFT/VERIFIED/BROKEN). 한 단어라 부피가 거의
 *   없는 반면, Agent가 `BROKEN`을 피하고 `VERIFIED`를 먼저 고르게 하는 근거가 된다.
 */
data class TestCaseCatalogEntry(
    val id: Long,
    val category: String,
    val title: String,
    val precondition: String?,
    val expected: String,
    @JsonProperty("verification_status")
    val verificationStatus: String,
)

/**
 * 프로젝트 TestCase 전량 목록의 REST 응답.
 *
 * 배열을 그대로 내지 않고 감싸는 것은 [TestCaseListResponse]와 같은 관례이기도 하고, 나중에 목록에
 * 곁들일 값(예: 상한에 걸려 잘렸는지)이 생겨도 와이어 모양이 깨지지 않게 하기 위해서다.
 * Agent에 보내는 `case_catalog`는 이 껍데기 없이 [items]만 싣는다 — 프롬프트에 렌더링될 값이라
 * 중첩이 한 겹이라도 적은 편이 낫다.
 */
data class TestCaseCatalogResponse(val items: List<TestCaseCatalogEntry>)
