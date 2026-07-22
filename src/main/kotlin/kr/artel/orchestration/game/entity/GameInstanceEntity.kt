package kr.artel.orchestration.game.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 프로젝트에 붙은 SDK 설치본 하나.
 *
 * [instanceKey]는 SDK가 가진 유일한 자격증명이다. 등록 요청과 웹소켓 핸드셰이크가 모두 이
 * 값으로 통과하며, 만료도 회전도 없다.
 *
 * [lastSdkUuid]는 마지막으로 등록한 런타임의 식별자로, 자격증명이 아니라 기록이다.
 * 어느 머신에서 마지막으로 실행됐는지 이상은 의미하지 않는다.
 *
 * [deletedAt]이 채워진 행은 삭제된 것으로 보고, 키 조회를 포함한 어떤 조회에도 나타나지
 * 않는다. 지운 인스턴스의 키가 계속 통하면 대시보드에서 지웠다는 사실이 무의미해진다.
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

    @Column("instance_key")
    val instanceKey: String,

    @Column("last_sdk_uuid")
    val lastSdkUuid: String? = null,

    @Column("last_connected_at")
    val lastConnectedAt: Instant? = null,

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
