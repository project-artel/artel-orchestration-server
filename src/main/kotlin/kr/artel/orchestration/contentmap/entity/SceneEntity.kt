package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * scene — 정적. evidence 순회가 만난 씬.
 *
 * 갱신 단위가 이것이다. 근거 문서는 씬 키로 관리되고 재방문하면 그 씬만 덮어쓴다.
 */
@Table("scene")
data class SceneEntity(
    @Id
    val id: Long? = null,

    @Column("content_map_id")
    val contentMapId: Long,

    @Column("name")
    val name: String,

    /**
     * 식별자를 남긴 설명. 무엇이 무엇을 판정하고 어디로 이어지는지.
     *
     * 순수 자연어로 옮기지 않는다 — 그러면 조인 키가 문장 밖으로 사라져 TC 가 그 문장을 읽고도
     * 무엇을 눌러야 할지 모른다.
     */
    @Column("summary")
    val summary: String? = null,

    /**
     * 근거의 `scenes[]` 에 이름만 있고 순회하지 못한 씬은 `false`. 런타임은 로드된 씬만 볼 수 있다.
     * `false` 면 이 씬의 기능이 비어 있는 것이 **정상**이다.
     */
    @Column("walked")
    val walked: Boolean = false,

    /** 화면 구분이 없을 때만 의미 있는 대표 이미지. 보통은 [ScreenEntity] 쪽이 진짜다. */
    @Column("image_object_key")
    val imageObjectKey: String? = null,

    /** 이 씬에 대해 근거가 "못 봤다"고 말한 것. `dont-destroy-on-load-not-walked` 등. */
    @Column("gaps")
    val gaps: Json = Json.of("[]"),

    @Column("scanned_at")
    val scannedAt: Instant? = null,
)
