package kr.artel.orchestration.testrun.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 저작 챗봇 대화 메시지(런 단위, 사용자별 프라이빗 스레드) — ARTEL-206 Step 6.
 *
 * 한 번의 대화로 여러 시나리오를 추가·수정하므로 스레드의 주체는 시나리오가 아니라 [testRunId]다.
 * 같은 런 안에서는 어떤 시나리오를 편집하든 대화가 이어진다. `appUserId`는 스레드 소유자(공유 안 함),
 * `role`로 사용자(USER)와 Agent(ASSISTANT)를 구분한다.
 */
@Table("test_run_message")
data class TestRunMessageEntity(
    @Id
    val id: Long? = null,

    @Column("test_run_id")
    val testRunId: Long,

    @Column("app_user_id")
    val appUserId: Long,

    @Column("role")
    val role: String,

    @Column("content")
    val content: String,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
