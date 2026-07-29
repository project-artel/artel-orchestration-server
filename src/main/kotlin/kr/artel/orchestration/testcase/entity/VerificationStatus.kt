package kr.artel.orchestration.testcase.entity

/**
 * TestCase의 검증 생명주기.
 * - [DRAFT]    : Agent가 작성/제안했으나 아직 실행 검증 안 됨(기본값).
 * - [VERIFIED] : 실행으로 기대효과 일치가 확인됨.
 * - [BROKEN]   : 실행에서 불일치(게임 변경 등)로 깨짐.
 *
 * 저장은 이름 그대로 VARCHAR + CHECK. [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시).
 */
enum class VerificationStatus {
    DRAFT,
    VERIFIED,
    BROKEN;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        fun fromWire(value: String?): VerificationStatus? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}
