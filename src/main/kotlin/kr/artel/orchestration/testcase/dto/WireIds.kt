package kr.artel.orchestration.testcase.dto

import kr.artel.orchestration.common.error.BadRequestException

/** 한 요청이 받는 id 배열의 상한. `IN (...)` bind 변수 수이기도 하다. */
internal const val WIRE_ID_LIMIT = 500

/**
 * 문자열로 온, 비어 있어도 되는 id 하나를 읽는다. id 는 JS 64비트 정밀도 손실을 피하려고 문자열로 오간다.
 *
 * null 과 빈 문자열은 "없음"(null) 이다 — `parentId`, `folderId` 가 모두 같은 규칙이다. 숫자로 읽히지
 * 않으면 400 이다. 그 프로젝트에 있을 수 없는 값이지만, 조용히 넘기면 home 쪽 버그가 엉뚱한 위치로만
 * 드러난다.
 */
internal fun parseOptionalWireId(value: String?, field: String): Long? {
    if (value.isNullOrBlank()) return null
    return value.toLongOrNull() ?: throw BadRequestException("$field 가 숫자로 된 id 가 아닙니다.")
}

/**
 * id 배열을 읽는다. null 이거나 0건이거나 [WIRE_ID_LIMIT] 건을 넘으면 400 이다. 상한은 중복을
 * 합치기 전 배열 길이로 세고, 중복은 합쳐 돌려준다. 원소가 null 이면 400 이다.
 */
internal fun parseWireIds(values: List<String?>?, field: String): List<Long> {
    if (values == null || values.size !in 1..WIRE_ID_LIMIT) {
        throw BadRequestException("$field 는 1건 이상 ${WIRE_ID_LIMIT}건 이하여야 합니다.")
    }
    return values.map { value ->
        value?.toLongOrNull() ?: throw BadRequestException("$field 에 숫자가 아닌 id 가 있습니다.")
    }.distinct()
}
