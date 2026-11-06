package kr.artel.orchestration.game.dto

import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * 게임 빌드 수정 요청. 부분 수정이라 null인 필드는 손대지 않는다.
 *
 * 지울 때는 null이 아니라 빈 문자열을 보낸다. Jackson은 "키가 없음"과 "값이 null"을 같은
 * null로 만들기 때문에, null에 "지우기" 의미를 주면 나머지 필드만 수정하려는 요청이
 * 나머지까지 지워버린다. 빈 문자열은 저장 시 null로 정규화한다.
 *
 * version은 여기에 없다. SDK가 관찰해 보고한 값이라 사람이 고칠 대상이 아니다.
 *
 * @property label 새 표시 이름. null이면 유지, 빈 문자열이면 삭제
 * @property notes 새 메모. null이면 유지, 빈 문자열이면 삭제
 */
data class UpdateGameBuildRequest(
    @field:Size(max = 80)
    val label: String? = null,

    @field:Size(max = 2000)
    val notes: String? = null
)

/**
 * 게임 빌드 응답.
 *
 * @property version Unity Player Settings에서 관찰한 값. 읽기 전용이다
 * @property isEditor editor 행인가(ARTEL-1014). editor 도 version 마다 한 행이고 그 version 의 버전이다(ARTEL-1180).
 * 같은 version 의 build 행과는 따로 있고, 버전을 올리지 않은 editor 재등록은 그 행의 스캔을 덮는다
 */
data class GameBuildResponse(
    val id: String,
    val projectId: String,
    val version: String,
    val label: String?,
    val notes: String?,
    val isEditor: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** 목록 응답 봉투. */
data class GameBuildListResponse(
    val items: List<GameBuildResponse>
)

/**
 * 빌드 하나의 요약 한 줄. 빌드 목록 화면이 빌드마다 따로 묻지 않고 이 한 응답으로 그린다.
 *
 * @property instanceCount `last_game_build_id` 가 이 빌드인 게임 인스턴스 수. 삭제된 인스턴스는 빠진다
 * @property connectedCount 그 인스턴스 중 지금 SDK 소켓이 붙어 있는 수
 * @property lastReportedAt 그 인스턴스들의 `last_connected_at` 중 가장 늦은 것. 인스턴스가 없으면 null
 * @property cheatCount `game_function_catalog.cheats` 의 길이. 목록을 보낸 적 없으면 0
 * @property behaviorCount `game_function_catalog.behaviors` 의 길이. 목록을 보낸 적 없으면 0
 * @property catalogReceivedAt 목록을 마지막으로 받은 시각. 보낸 적 없으면 null
 * @property qaRunCount 이 빌드로 돈 QA run 수
 * @property latestQaRun `started_at` 이 가장 늦은 run. run 이 없으면 null
 */
data class GameBuildSummaryResponse(
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val isEditor: Boolean,
    val createdAt: Instant,
    val lastReportedAt: Instant?,
    val instanceCount: Int,
    val connectedCount: Int,
    val cheatCount: Int,
    val behaviorCount: Int,
    val catalogReceivedAt: Instant?,
    val qaRunCount: Int,
    val latestQaRun: LatestQaRunSummary?
)

/**
 * @property stepsPassed 그 run 의 try 들이 합산한 통과 step 수. 끝난 try 가 하나도 없으면 null
 * @property stepsTotal 같은 합산의 전체 step 수. 끝난 try 가 하나도 없으면 null
 */
data class LatestQaRunSummary(
    val qaRunId: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val stepsPassed: Int?,
    val stepsTotal: Int?
)

data class GameBuildSummaryListResponse(
    val items: List<GameBuildSummaryResponse>
)
