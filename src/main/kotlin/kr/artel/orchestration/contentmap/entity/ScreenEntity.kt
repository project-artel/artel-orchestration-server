package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * screen — 런타임. QA 런이 관측한 실제 화면 상태.
 *
 * **씬 하나에 화면이 여럿일 수 있다.** 오버레이·팝업·상태 분기. 정적 분석으로는 알 수 없어
 * QA 런 전에는 0행이고 그게 정상이다.
 *
 * 씬으로 뭉개면 TC 가 깨진다 — 이어하기 버튼이 켜진 화면과 꺼진 화면이 같은 씬인데, "그 버튼을
 * 눌러라"는 TC 가 절반의 경우에 실패하고 agent 가 그것을 결함으로 보고한다.
 *
 * [name] 은 표시용이고 조인 키가 아니다. 기계는 [discriminator] 로 판정하고 이름은 LLM 이 짓는다.
 */
@Table("screen")
data class ScreenEntity(
    @Id
    val id: Long? = null,

    @Column("scene_id")
    val sceneId: Long,

    @Column("name")
    val name: String? = null,

    /**
     * 이 화면임을 판정하는 pulse 관측 조건.
     * `[{"selector":"Canvas[2]/continue[2]","active":true}]`
     */
    @Column("discriminator")
    val discriminator: Json,

    @Column("image_object_key")
    val imageObjectKey: String? = null,

    @Column("image_captured_at")
    val imageCapturedAt: Instant? = null,

    @Column("first_seen_qa_run_id")
    val firstSeenQaRunId: Long? = null,

    @Column("observed_count")
    val observedCount: Int = 0,
)
