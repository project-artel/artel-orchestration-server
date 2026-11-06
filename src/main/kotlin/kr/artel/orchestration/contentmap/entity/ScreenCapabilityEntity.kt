package kr.artel.orchestration.contentmap.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * screen_capability — 화면이 실제로 제공한 기능. 씬 목록의 **부분집합**이다.
 *
 * 화면이 자기 기능 목록을 따로 갖지 않는 이유: 두 벌 두면 갈라진다. 정적 근거가 아는 것은 "이
 * 타입이 이 씬에 놓였다"까지고, 어느 화면 상태에서 실제로 눌리는지는 런타임만 안다.
 *
 * 복합 PK 라 `@Id` 가 없다 — R2DBC 는 복합키 엔티티의 자동 저장을 지원하지 않으므로 적재는
 * 명시 INSERT/UPDATE 로 한다.
 */
@Table("screen_capability")
data class ScreenCapabilityEntity(
    @Column("screen_id")
    val screenId: Long,

    @Column("capability_id")
    val capabilityId: Long,

    @Column("observed_count")
    val observedCount: Int = 0,

    /** 눌렀을 때 실제로 무언가 변한 횟수. [observedCount] 와의 차이가 결함 신호다. */
    @Column("fired_count")
    val firedCount: Int = 0,
)
