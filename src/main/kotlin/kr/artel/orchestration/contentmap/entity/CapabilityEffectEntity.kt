package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * capability_effect — 명세의 `then`. 무엇이 달라지나.
 *
 * 근거가 말한 효과와 실제로 관측된 효과를 **나란히** 둔다([origin]). 어긋나는 것이 결함 신호이고,
 * 관측에만 있는 것이 근거의 구멍이다 — 서드파티 트윈처럼 정적으로 안 잡히는 것.
 *
 * [category] 가 `then` 슬롯을 가른다. [EffectCategory.STATE] 는 결과로 쓰지 않는다 — 이름으로 화면
 * 변화를 짐작하면 조용히 틀린다. 실측에서 47건이 이름 기반 오분류였다.
 */
@Table("capability_effect")
data class CapabilityEffectEntity(
    @Id
    val id: Long? = null,

    @Column("capability_id")
    val capabilityId: Long,

    /** [EffectOrigin] 중 하나. */
    @Column("origin")
    val origin: String = EffectOrigin.EVIDENCE.wire,

    /** [EffectCategory] 중 하나. */
    @Column("category")
    val category: String,

    /**
     * 문장의 동사. `scene` · `ui-value` · `transform` · `animation` · `audio` · `instantiate` ·
     * `destroy` · `active-state` · `saved` · `write`.
     */
    @Column("kind")
    val kind: String,

    @Column("target")
    val target: String? = null,

    /** 문장을 완성하는 값(`+1` · `false` · `Map_scene`). null 이면 "값이 바뀐다"까지만 쓸 수 있다. */
    @Column("detail")
    val detail: String? = null,

    /**
     * pulse 가 이 값을 되읽을 수 있나. **판정 가능 여부의 상한이다.**
     *
     * 실측 `watching 111 / unwatchable 1,126` — 읽을 수 있는 것이 9% 다. 굽는 쪽이 watch 대상을
     * 고르는 기준이 관련성이 아니라 능력이라, 호출 결과와 지역변수는 거부된다(관찰이 게임 상태를
     * 바꾸면 안 되므로 임의 메서드를 부르지 않는다).
     */
    @Column("watchable")
    val watchable: Boolean = false,
)
