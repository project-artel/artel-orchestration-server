package kr.artel.orchestration.testcase.dto

/**
 * 케이스 하나가 가리키는 **조작의 기계값**.
 *
 * @property input 스텝의 `input` 칸에 그대로 들어가는 값 — `key:Return` · `click:Canvas/continue`.
 *   누를 것이 없으면 빈 문자열이다.
 */
data class CaseInputRow(
    val testCaseId: Long,
    val input: String,
)
