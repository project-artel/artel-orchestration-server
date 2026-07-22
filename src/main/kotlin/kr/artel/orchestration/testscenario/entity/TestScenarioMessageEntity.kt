package kr.artel.orchestration.testscenario.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * TestScenario 작성 중 오간 채팅 메시지(사용자별 프라이빗 스레드).
 *
 * `appUserId`는 이 대화 스레드의 소유자로, USER/ASSISTANT 메시지 모두 같은 값을 가진다(공유 안 함).
 * `role`로 사용자(USER)와 Agent(ASSISTANT)를 구분하고, `content`는 메시지 텍스트다.
 */
@Table("test_scenario_message")
data class TestScenarioMessageEntity(
    @Id
    val id: Long? = null,

    @Column("test_scenario_id")
    val testScenarioId: Long,

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
