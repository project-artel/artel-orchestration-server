package kr.artel.orchestration.testcase.entity

import kr.artel.orchestration.common.error.BadRequestException

/** TC 검토 상태. READY는 사용 가능하다는 뜻이며 실행 검증 통과 여부와 별개다. */
enum class ReviewStatus {
    DRAFT,
    READY;

    companion object {
        fun parse(value: String): String = entries.firstOrNull { it.name == value.trim().uppercase() }?.name
            ?: throw BadRequestException("reviewStatus must be one of DRAFT, READY")
    }
}
