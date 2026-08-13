package kr.artel.orchestration.knowledge.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 한 QA 런이 적용한 지식 쓰기 프레임 하나(ARTEL-364). 멱등 원장이다.
 *
 * `(qa_try_id, message_id)`가 유일하고, 그 유일성이 재전송을 흡수한다 — 같은 프레임이 두 번 와도
 * 두 번째 삽입이 걸리고, 서비스가 그 충돌을 값으로 바꿔 첫 번째가 남긴 id로 답한다. `issue`가
 * `uk_issue_message`로 하는 것과 같은 발상이다(V12).
 *
 * **삽입은 반드시 쓰기와 같은 트랜잭션이다.** 그것이 `issue`와 다른 점이고 이 테이블이 성립하는
 * 조건이다. 저쪽은 message_id가 이슈 행 자신에 있어 삽입 충돌이 곧 중복 차단이지만, 여기서는
 * 지식 쓰기가 먼저 일어난다 — 원장이 트랜잭션 밖이면 충돌을 알았을 때 중복은 이미 만들어져 있다.
 *
 * @property type 무엇을 한 프레임인지. 재전송에 답할 때 응답 payload의 `type`으로 그대로 나가므로,
 *   원장이 그 값을 알고 있어야 재전송의 답이 첫 번째의 답과 같아진다.
 * @property knowledgeId 항목 쓰기가 남긴 행. **그 런의 스코프에서 사실을 지고 있는 행**이다 —
 *   스코프 런이 baseline을 고쳤거나 지웠으면 그림자나 툼스톤이다. baseline id를 남기면 재전송의
 *   답이 그 런에서 다시 지목할 수 없는 id가 된다.
 * @property edgeId 관계 쓰기가 남긴 간선. [knowledgeId]와 둘 중 하나만 찬다(DB CHECK).
 */
@Table("qa_knowledge_write")
data class QaKnowledgeWriteEntity(
    @Id
    val id: Long? = null,

    @Column("qa_try_id")
    val qaTryId: Long,

    @Column("message_id")
    val messageId: String,

    @Column("type")
    val type: String,

    @Column("knowledge_id")
    val knowledgeId: Long? = null,

    @Column("edge_id")
    val edgeId: Long? = null,

    @Column("created_at")
    val createdAt: Instant? = null,
)
