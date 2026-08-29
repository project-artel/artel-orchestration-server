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

    /**
     * 이 행을 누가 만들었나. `pulse-diff` 또는 `agent`(ARTEL-644).
     *
     * `pulse` diff 행은 "값이 달라졌다" 는 측정이고 agent 행은 "됐다 / 안 됐다" 는 [verdict] 다.
     * 가르는 칸이 없으면 둘이 같은 사실로 읽힌다. `ck_capability_observation_shape` 가 source
     * 별로 어느 칸이 차야 하는지를 강제한다.
     */
    @Column("source")
    val source: String = ObservationSource.PULSE_DIFF.wire,

    @Column("acted_at")
    val actedAt: Instant,

    /**
     * agent 가 실제로 보낸 프로토콜 메서드. `button_click` 등.
     *
     * agent 행에서는 null 일 수 있다. 실측 capability 472 개 중 418 개가 `interaction = none`
     * 이라 보낼 메서드가 애초에 없다.
     */
    @Column("action_method")
    val actionMethod: String? = null,

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
    val fired: Boolean? = null,

    /** 실제로 달라진 것. 기대와 대조해 verification 을 정한다. */
    @Column("observed_effects")
    val observedEffects: Json = Json.of("[]"),

    /** agent 의 verdict(ARTEL-644). `CapabilityVerdict` 중 하나이고 `pulse` diff 행에서는 null 이다. */
    @Column("verdict")
    val verdict: String? = null,

    /**
     * 무엇을 보고 그렇게 말했나(ARTEL-644).
     *
     * **verdict 만 받으면 나중에 그것이 맞았는지 확인할 길이 없다.** agent 행에서는 비어 있을 수
     * 없다 — CHECK 가 강제한다.
     */
    @Column("rationale")
    val rationale: String? = null,

    /** 캡처가 있으면 그것도 근거가 된다. `qa_log` 의 SCREENSHOT 행 message_id 다. */
    @Column("capture_id")
    val captureId: String? = null,

    /** 런 안의 어느 try 가 적었나. [qaRunId] 만으로는 시나리오 여럿을 도는 런에서 갈리지 않는다. */
    @Column("qa_try_id")
    val qaTryId: Long? = null,

    /** 이 행을 만든 frame 의 messageId. 멱등 키가 아니라 되짚기용이다. */
    @Column("agent_message_id")
    val agentMessageId: String? = null,
)
