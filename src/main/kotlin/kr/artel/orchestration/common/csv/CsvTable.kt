package kr.artel.orchestration.common.csv

/**
 * CSV 한 장을 문자열 표로 읽어 둔 것. 값은 해석하지 않는다 — 숫자로 보이는 칸도 문자열 그대로다.
 *
 * 타입 추론을 하지 않는 이유는 아래 두 소비처 모두에서 원본을 그대로 보존해야 하기 때문이다.
 * XLSX로 내보낼 때 숫자로 바꾸면 `"00123"`의 앞자리 0과 긴 ID의 정밀도가 사라지고,
 * TestCase로 적재할 때는 애초에 전부 자연어 필드다.
 *
 * @property headers 첫 줄. 열 이름으로 값을 찾을 때 쓴다
 * @property rows 헤더를 제외한 줄들. 각 줄은 [headers]와 같은 길이로 맞춰져 있다(짧은 줄은 빈 문자열로 채움)
 */
data class CsvTable(
    val headers: List<String>,
    val rows: List<List<String>>
) {
    /** 헤더 이름 → 열 인덱스. 이름 비교는 대소문자·앞뒤 공백을 무시한다. */
    private val indexByHeader: Map<String, Int> =
        headers.withIndex().associate { (index, name) -> name.normalizeHeader() to index }

    val rowCount: Int get() = rows.size

    /** 헤더 이름으로 값을 꺼낸다. 그런 열이 없거나 값이 비면 null. */
    fun value(row: List<String>, header: String): String? {
        val index = indexByHeader[header.normalizeHeader()] ?: return null
        return row.getOrNull(index)?.trim()?.ifBlank { null }
    }

    fun hasHeader(header: String): Boolean = indexByHeader.containsKey(header.normalizeHeader())

    companion object {
        /** `Test Case`, `test_case`, `TESTCASE`를 같은 열로 본다. Agent가 내보내는 표기를 고정할 수 없다. */
        internal fun String.normalizeHeader(): String =
            trim().lowercase().replace(HEADER_NOISE, "")

        private val HEADER_NOISE = Regex("[\\s_-]")
    }
}
