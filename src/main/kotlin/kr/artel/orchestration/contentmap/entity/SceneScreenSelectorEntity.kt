package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 이 씬에서 화면을 식별하는 selector 목록의 항목 하나 (ARTEL-654).
 *
 * `discriminator` 는 이 목록에 맞는 selector 만 담는다. **목록에 없는 것은 처음 보는 것이어도
 * 무시한다** — 그 기본값을 뒤집은 근거는 `V60__whitelist_screen_defining_selectors.sql` 에 있다.
 *
 * [pattern] 은 정확 문자열이고 정규식이 아니다. 평가가 Kotlin(`ScreenSelectorWhitelist`)과
 * SQL(`screen_defining_selector`) 양쪽에서 일어나는데, 두 정규식 엔진이 다르면 같은 화면이 두
 * `discriminator` 로 갈린다.
 */
@Table("scene_screen_selector")
data class SceneScreenSelectorEntity(
    @Id
    val id: Long? = null,

    @Column("scene_id")
    val sceneId: Long,

    /** [ScreenSelectorMatch.wire]. */
    @Column("match_kind")
    val matchKind: String,

    /** 맞대 볼 정확 문자열. */
    @Column("pattern")
    val pattern: String,

    /** [ScreenSelectorSource.wire]. */
    @Column("source")
    val source: String,

    /** 이 대상이 화면을 식별하는가. `false` 는 넓은 항목에 구멍을 내는 명시적 제외다. */
    @Column("screen_defining")
    val screenDefining: Boolean = true,

    @Column("created_at")
    val createdAt: Instant? = null,
)
