package kr.artel.orchestration.testcase.dto

/**
 * 게임을 켜면 값이 무엇으로 시작하는가(ARTEL-665).
 *
 * [detail] 은 지도가 적은 그대로다 — `PlayerPrefs.GetInt("StagePosition", -1)`. 기본값을 뽑는
 * 일은 읽는 쪽이 한다. 여기서 뽑아 두면 그 규칙이 SQL 에 숨고, 엔진이 바뀌면 찾기 어려워진다.
 */
data class StartingValue(
    val name: String,
    val detail: String,
)
