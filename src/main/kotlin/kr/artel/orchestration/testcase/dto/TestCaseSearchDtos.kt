package kr.artel.orchestration.testcase.dto

/**
 * TestCase 벡터 검색 결과 한 항목(ARTEL-206).
 *
 * @property id 케이스 id(문자열). 저작 에이전트가 이 값을 caseIds로 되돌려 시나리오에 연결한다.
 * @property verificationStatus DRAFT/VERIFIED/BROKEN. 에이전트가 VERIFIED를 우선하거나 BROKEN을
 *   피하는 판단에 쓴다.
 * @property score 코사인 유사도(1에 가까울수록 가깝다). 원시 거리 대신 유사도로 내보내는 이유는
 *   이 값이 Agent 프롬프트로 들어가서인데, "작을수록 좋다"는 거리는 거꾸로 읽히기 쉽다.
 */
data class TestCaseSearchHit(
    val id: String,
    val category: String,
    val title: String,
    val precondition: String?,
    val expected: String,
    val verificationStatus: String,
    val score: Double,
)

/**
 * TestCase 검색 응답.
 *
 * [results]가 비는 것은 오류가 아니다. 백필이 비동기라 방금 적재된 케이스에 아직 벡터가 없는 상태가
 * 정상이고, 그때 답은 "없음"이다.
 *
 * @property model 어느 임베딩 모델로 검색했는지. 결과가 계속 비면 Agent 설정과 맞춰 봐야 하므로 싣는다.
 */
data class TestCaseSearchResponse(
    val query: String,
    val model: String,
    val results: List<TestCaseSearchHit>,
)
