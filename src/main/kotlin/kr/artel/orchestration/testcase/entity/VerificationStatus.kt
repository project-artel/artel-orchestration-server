package kr.artel.orchestration.testcase.entity

/**
 * TestCase의 검증 생명주기.
 * - [DRAFT]    : Agent가 작성/제안했으나 아직 실행 검증 안 됨(기본값).
 * - [VERIFIED]   : 실행으로 기대효과 일치가 확인됨. QA 검증 스텝이 통과하면 이 값이 된다(ARTEL-994).
 * - [FAILED]     : QA 검증 스텝이 실패함(ARTEL-994).
 * - [REGRESSION] : 앞 버전에서 판정이 났고 이번 버전에서 다시 검증해야 함(ARTEL-994). build 별 생성이
 *                  직전 build 의 같은 `case_key` 를 보고 붙인다.
 * - [BROKEN]     : 지도 재생성에서 사라졌지만 시나리오가 인용해 남은 행.
 *
 * 저장은 이름 그대로 VARCHAR + CHECK. [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시).
 */
enum class VerificationStatus {
    DRAFT,
    VERIFIED,
    FAILED,
    REGRESSION,
    BROKEN;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        fun fromWire(value: String?): VerificationStatus? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}
