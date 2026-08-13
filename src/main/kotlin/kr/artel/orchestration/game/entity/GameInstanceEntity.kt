package kr.artel.orchestration.game.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 프로젝트에 붙은 SDK 설치본 하나.
 *
 * [sdkUuid]는 런타임이 스스로 만들어 보관하는 설치 식별자다. 자격증명이 아니다. 요청자가
 * 누구인지는 SDK 토큰이 말하고, 이 값은 그 사용자의 어느 설치본인지만 가른다. 그래서 값을
 * 훔쳐도 토큰 없이는 쓸 수 없다.
 *
 * 대시보드에서 만든 인스턴스는 아직 SDK가 붙기 전이라 [sdkUuid]가 비어 있다. 첫 등록에서
 * 채워진다.
 *
 * [deletedAt]이 채워진 행은 삭제된 것으로 보고 어떤 조회에도 나타나지 않는다. 지운
 * 인스턴스가 계속 통하면 대시보드에서 지웠다는 사실이 무의미해진다.
 */
@Table("game_instance")
data class GameInstanceEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("name")
    val name: String,

    @Column("platform")
    val platform: String,

    @Column("sdk_uuid")
    val sdkUuid: String? = null,

    @Column("last_connected_at")
    val lastConnectedAt: Instant? = null,

    /**
     * 마지막 등록에서 SDK가 보고한 빌드.
     *
     * 등록은 원래 빌드를 응답으로만 돌려주고 인스턴스에는 남기지 않았다. 웹소켓이 열릴 때
     * "지금 붙은 런타임이 어느 빌드인가"를 알 방법이 없으면 성능 런을 빌드에 묶을 수 없어
     * 빌드 추세가 성립하지 않는다(ARTEL-378). 그래서 등록이 그 값을 여기 남긴다.
     *
     * 자격증명이 아니라 기록이다. 대시보드에서 만들었지만 아직 SDK가 붙지 않은 인스턴스는
     * 비어 있다.
     */
    @Column("last_game_build_id")
    val lastGameBuildId: Long? = null,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant,

    @Column("deleted_at")
    val deletedAt: Instant? = null
)

/**
 * 인스턴스가 올라간 실행 환경.
 *
 * 지금은 Unity만 지원한다. 값을 하나만 두는 것은 SDK가 실제로 하나뿐이기 때문이며,
 * 지원하지 않는 플랫폼을 미리 넣어두면 서버가 받아들이는 값과 실제로 동작하는 값이
 * 어긋난다.
 */
enum class GamePlatform {
    UNITY
}
