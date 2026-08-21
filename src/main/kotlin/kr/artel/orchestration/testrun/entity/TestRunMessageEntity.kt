package kr.artel.orchestration.testrun.entity

import org.springframework.data.annotation.CreatedDate
import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 작성 챗봇 대화 메시지(런 단위, 사용자별 프라이빗 스레드) — ARTEL-206 Step 6.
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

    /**
     * 구조적 본문. 지금은 되묻는 질문 하나가 쓴다(ARTEL-487).
     *
     * 사람이 읽는 문장은 [content] 에 그대로 있고, 이 칸은 **화면이 눌러야 할 것**을 담는다 —
     * 선택지 없이 문장만 있으면 사용자는 무엇을 답해야 하는지 알 수 없고, SSE 로만 흘리면
     * 새로고침 한 번에 선택지가 사라져 답할 방법만 없어진다.
     *
     * 종류는 본문 안의 `kind` 가 말한다. 앞으로 생길 구조적 본문도 같은 칸을 쓴다.
     */
    @Column("payload")
    val payload: Json? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
