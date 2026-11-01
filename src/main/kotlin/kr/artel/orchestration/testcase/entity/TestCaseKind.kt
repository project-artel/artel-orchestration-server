package kr.artel.orchestration.testcase.entity

import kr.artel.orchestration.common.error.BadRequestException

/**
 * TC 종류(ARTEL-1306).
 *
 * TC 는 검증할 기능 하나다. 같은 기능을 잘게 나누면 사람이 관리할 수 없어 거절 · 되풀이 막기는 기능 TC 로 쓰지 않는데,
 * 결함은 막아야 할 때 막지 못하는 쪽에서 자주 나온다. 그래서 그런 확인은 [EXCEPTION] 으로 따로 둔다 — 기능 목록은 사람이
 * 읽을 크기로 남고, 저작은 여정에 기능 TC 만 잇는다.
 */
enum class TestCaseKind {
    FUNCTION,
    EXCEPTION;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        /** 와이어 값을 읽는다. null 이면 null(정하지 않음)이고, 모르는 값은 400 이다. */
        fun parse(value: String?): TestCaseKind? =
            value?.trim()?.uppercase()?.let { normalized ->
                entries.firstOrNull { it.name == normalized }
                    ?: throw BadRequestException("kind must be one of $NAMES")
            }
    }
}
