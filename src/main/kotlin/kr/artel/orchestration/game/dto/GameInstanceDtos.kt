package kr.artel.orchestration.game.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kr.artel.orchestration.game.entity.GamePlatform
import java.time.Instant

/**
 * 게임 인스턴스 수정 요청. 부분 수정이라 null인 필드는 손대지 않는다.
 *
 * 이름 말고는 고칠 것이 없다. 플랫폼은 SDK가 이미 그 위에서 돌고 있어 바꾸면 실제와
 * 어긋난다.
 *
 * @property name 새 이름. null이면 유지
 */
data class UpdateGameInstanceRequest(
    @field:Size(min = 1, max = 80)
    val name: String? = null
)

/**
 * 게임 인스턴스 응답.
 *
 * id는 내부적으로 Long이지만 문자열로 내보낸다. 다른 응답이 이미 그렇게 하고 있고,
 * 클라이언트는 이 값을 파싱하지 않는 불투명 식별자로 다룬다.
 *
 * @property connected 지금 웹소켓이 붙어 있는지. 마지막 조회 시점의 값이며 구독이 아니다
 * @property lastConnectedAt 마지막으로 등록에 성공한 시각. 한 번도 없으면 null
 */
data class GameInstanceResponse(
    val id: String,
    val projectId: String,
    val name: String,
    val platform: GamePlatform,
    val connected: Boolean,
    val lastConnectedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** 목록 응답 봉투. */
data class GameInstanceListResponse(
    val items: List<GameInstanceResponse>
)

/**
 * 게임 인스턴스 초기화 요청. 본문이 아예 없어도 된다 — 그때는 [clearPlayerPrefs]가 기본값 false다.
 *
 * @property clearPlayerPrefs 씬 리로드에 더해 SDK 의 PlayerPrefs(저장소)도 비울지
 */
data class ResetGameInstanceRequest(
    val clearPlayerPrefs: Boolean = false
)

/**
 * 게임 인스턴스 초기화 응답. 202 다 — 게임이 리셋을 끝냈다는 뜻이 아니라 명령을 전송 줄에
 * 세웠다는 뜻이다([kr.artel.orchestration.sdk.service.SessionManager.send]의 계약과 같다).
 */
data class GameInstanceResetResponse(
    val gameInstanceId: String,
    val clearPlayerPrefs: Boolean,
    val requestedAt: Instant
)
