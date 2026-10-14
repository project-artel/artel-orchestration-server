package kr.artel.orchestration.game.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * SDK가 보고한 게임 버전.
 *
 * [version]은 Unity Player Settings에서 관찰한 값이라 사람이 고칠 수 없다. 고치는 순간
 * SDK가 다음에 보고할 값과 어긋나 같은 빌드가 두 행이 된다. 사람이 붙이는 설명은
 * [label]과 [notes]로 따로 받는다.
 *
 * 인스턴스가 아니라 프로젝트에 매단다. 같은 빌드를 여러 인스턴스가 돌려도 빌드는 하나여야
 * 하고, 인스턴스를 지웠다고 그 빌드 기록이 사라져서도 안 된다.
 *
 * [sceneScan]은 SDK가 등록 시 보고한 씬 스캔 JSON을 그대로 담는다. 빌드당 최신본 하나만
 * 의미가 있어 재등록마다 덮어쓴다. null은 아직 스캔을 보고한 적 없다는 뜻이다.
 *
 * [isEditor]가 true인 행은 Unity editor 실행을 받는 프로젝트당 한 행이다(ARTEL-1014). editor 는
 * development build 와 같은 Player Settings 버전을 보고하지만 계속 바뀌는 작업 상태라 게임 버전이
 * 아니다. 그래서 이 행의 [version]과 [sceneScan]은 editor 가 등록할 때마다 마지막 보고값으로 바뀌고,
 * 빌드를 버전 순서로 읽는 곳(TR 의 버전, 가져오기 트리, REGRESSION 의 직전 build, 판정 이력)은 이 행을
 * 빼고 읽는다.
 */
@Table("game_build")
data class GameBuildEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("version")
    val version: String,

    @Column("label")
    val label: String? = null,

    @Column("notes")
    val notes: String? = null,

    @Column("scene_scan")
    val sceneScan: Json? = null,

    @Column("is_editor")
    val isEditor: Boolean = false,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
)
