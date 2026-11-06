package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 목록에 없는 selector 를 물어본 기록 한 행 (ARTEL-655).
 *
 * **이 표의 존재 이유는 다시 묻지 않는 것이다.** 없으면 카드를 뽑을 때마다 제안이 하나씩 나간다 —
 * 실측 `TurnBattleScene` 의 한 `pulse` 에 selector 가 62 개 있었고 그중 목록 밖이 59 개인데, `pulse` 는
 * 초당 여러 번 온다.
 *
 * 프로세스 메모리에 두지 않는 이유는 `scene_screen_selector` 와 같다. 재시작하면 사라지고 서버가 두
 * 대면 각자 자기 것을 보므로, "한 번만 묻는다" 가 재시작마다 거짓이 된다.
 *
 * 답이 와도 행은 남고 [status] 만 `answered` 가 된다. 지우면 물어본 적 있다는 사실이 사라져 다시
 * 묻게 되고, 그것이 이 표가 막으려던 바로 그것이다.
 */
@Table("screen_selector_proposal")
data class ScreenSelectorProposalEntity(
    @Id
    val id: Long? = null,

    @Column("scene_id")
    val sceneId: Long,

    /** [ScreenSelectorProposalReason.wire]. */
    @Column("reason")
    val reason: String,

    /** 물어본 selector 원문. `scene-screen-cap` 은 대상이 씬 자체라 빈 문자열이다. */
    @Column("selector")
    val selector: String,

    /** `outstanding` 또는 `answered`. */
    @Column("status")
    val status: String,

    /** 나간 프레임의 `messageId`. 답의 `correlationId` 가 이 값이다. */
    @Column("message_id")
    val messageId: String? = null,

    @Column("asked_qa_run_id")
    val askedQaRunId: Long? = null,

    @Column("asked_at")
    val askedAt: Instant? = null,

    @Column("answered_at")
    val answeredAt: Instant? = null,
)

/** [ScreenSelectorProposalEntity.status] 의 값. */
object ScreenSelectorProposalStatus {
    const val OUTSTANDING = "outstanding"
    const val ANSWERED = "answered"
}
