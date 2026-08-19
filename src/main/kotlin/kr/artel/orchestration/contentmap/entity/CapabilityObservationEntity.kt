package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * capability_observation — origin 무관하게 모든 기능이 여기 쌓인다. 조작 한 번이 한 행.
 *
 * pulse 는 **"값이 어떻게 달라졌나"까지만** 말하고 무엇 때문인지는 말하지 않는다. SDK 쪽에서
 * "눌린 것이 실제로 발화했는지"를 싣는 길을 한 번 만들었다가 뺐다 — 상태 채널이 무엇을 재는지
 * 흐려지기 때문이다. 그래서 인과는 읽는 쪽이 세우고, 채널 둘을 판독 번호로 잇는 것이 이 표다.
 *
 * ```
 * 액션 채널 (ACTION_RESULT)   "클릭을 보냈고 성공했다"           t
 * 상태 채널 (pulse)           "reading n 에서 값이 이렇게 변했다"  t+α
 * ```
 *
 * 실제로 보낸 메서드와 인자를 여기 남긴다. **재현이 필요한 곳은 content_map 이 아니라 이
 * 기록이다** — agent 가 런마다 다시 정해도 무엇을 보냈는지는 남아 있어야 한다.
 */
@Table("capability_observation")
data class CapabilityObservationEntity(
    @Id
    val id: Long? = null,

    @Column("capability_id")
    val capabilityId: Long,

    @Column("qa_run_id")
    val qaRunId: Long,

    @Column("screen_id")
    val screenId: Long? = null,

    @Column("acted_at")
    val actedAt: Instant,

    /** agent 가 실제로 보낸 프로토콜 메서드. `button_click` 등. */
    @Column("action_method")
    val actionMethod: String,

    @Column("action_params")
    val actionParams: Json = Json.of("{}"),

    /**
     * 첫 메서드가 거절당해 다른 것으로 바꿔 성공한 횟수. `> 1` 이 쌓이는 자리가 힌트가 나쁜
     * 자리이고, 매핑 규칙을 고칠 지점을 알려준다.
     */
    @Column("attempts")
    val attempts: Int = 1,

    @Column("reading_before")
    val readingBefore: Long? = null,

    @Column("reading_after")
    val readingAfter: Long? = null,

    /**
     * 눌렀는데 아무 값도 안 변했나. **결함 후보의 1차 신호.**
     *
     * `false` 가 "버튼이 고장났다"와 "관측 가능한 것이 아무것도 없었다" 둘 다일 수 있으므로,
     * [CapabilityEffectEntity.watchable] 을 함께 봐야 갈린다.
     */
    @Column("fired")
    val fired: Boolean,

    /** 실제로 달라진 것. 기대와 대조해 검증 상태를 정한다. */
    @Column("observed_effects")
    val observedEffects: Json = Json.of("[]"),
)
