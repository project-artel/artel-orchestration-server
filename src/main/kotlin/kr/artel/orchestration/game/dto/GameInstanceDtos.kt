package kr.artel.orchestration.game.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kr.artel.orchestration.game.entity.GamePlatform
import java.time.Instant

/**
 * 게임 인스턴스 생성 요청.
 *
 * @property name 인스턴스 이름. 사람이 어느 설치본인지 알아보는 용도라 자유롭게 쓴다
 * @property platform 실행 환경. 지금은 UNITY만 받는다
 */
data class CreateGameInstanceRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,

    @field:NotNull
    val platform: GamePlatform
)

/**
 * 게임 인스턴스 수정 요청. 부분 수정이라 null인 필드는 손대지 않는다.
 *
 * 이름 말고는 고칠 것이 없다. 플랫폼은 SDK가 이미 그 위에서 돌고 있어 바꾸면 실제와
 * 어긋나고, instance_key는 바꾸는 순간 설치된 SDK가 전부 연결을 잃는다.
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
 * @property instanceKey SDK에 붙여넣을 영구 자격증명. 한 번만 보여주지 않고 계속 다시
 *   읽을 수 있다. 재발급 수단이 없는데 한 번만 보여주면 SDK를 다시 설치할 때 막힌다
 * @property connected 지금 웹소켓이 붙어 있는지. 마지막 조회 시점의 값이며 구독이 아니다
 * @property lastConnectedAt 마지막으로 등록에 성공한 시각. 한 번도 없으면 null
 */
data class GameInstanceResponse(
    val id: String,
    val projectId: String,
    val name: String,
    val platform: GamePlatform,
    val instanceKey: String,
    val connected: Boolean,
    val lastConnectedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** 목록 응답 봉투. */
data class GameInstanceListResponse(
    val items: List<GameInstanceResponse>
)
