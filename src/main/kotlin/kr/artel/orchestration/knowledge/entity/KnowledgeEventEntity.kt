package kr.artel.orchestration.knowledge.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * knowledge 항목 하나에 일어난 변경 한 건(ARTEL-255).
 *
 * V19가 `knowledge` 행에 마지막 수정자만 남긴 것과 달리, 이쪽은 **버려지지 않는 이력**이다.
 * "어떤 QA 런이 만든 지식을 나중 런이 지웠나"가 QA 에이전트 비교의 결과 지표가 되는데, 행 하나에
 * 최신 상태만 있으면 그 질문에 답할 수 없다.
 *
 * 행 갱신과 이 이벤트 삽입은 **반드시 같은 트랜잭션**이다. 쪼개지면 `knowledge.version`과
 * 이 테이블의 최대 content 버전이 어긋난 채 굳고, 그 상태는 조용히 지나간다.
 *
 * @property qaTryId 이 변경을 일으킨 QA 런. 사람/문서 경로는 null이며, 지표의 귀속이 전부
 *   이 값에서 나온다.
 * @property version 이 이벤트가 만든 content 버전. DELETE/RESTORE는 버전을 만들지 않으므로
 *   **직전 버전을 그대로** 싣는다(V26 주석 참조).
 * @property after content 스냅샷 `{tag, summary, description}`. DELETE/RESTORE는 본문을 바꾸지
 *   않으므로 null이고, 그 null이 곧 "이 이벤트는 버전을 만들지 않았다"는 표식이다 —
 *   `uq_knowledge_event_version` 부분 유니크 인덱스가 이 조건 위에 서 있다.
 *
 *   `before`를 두지 않은 이유는 V26 주석에 있다: `after`만 두면 모든 버전이
 *   `(knowledge_id, version)` 단건 조회로 통일되고, 이벤트 행은 불변이라 최신을 읽는 사이
 *   mutation이 끼어드는 경합이 없다.
 */
@Table("knowledge_event")
data class KnowledgeEventEntity(
    @Id
    val id: Long? = null,

    @Column("knowledge_id")
    val knowledgeId: Long,

    /**
     * knowledge를 조인하면 얻을 수 있지만 복사해 둔다. 이력은 원본이 지워진 뒤에도 남아야 하고,
     * 그때 프로젝트 스코프를 잃으면 이력만으로는 아무것도 걸러 읽을 수 없다.
     */
    @Column("project_id")
    val projectId: Long,

    @Column("qa_try_id")
    val qaTryId: Long? = null,

    @Column("event")
    val event: String,

    @Column("version")
    val version: Int,

    @Column("after")
    val after: Json? = null,

    /**
     * 컬럼 기본값에 맡기지 않고 `Clock`으로 stamp한다 — R2DBC가 insert 후 기본값을 다시 읽지 않아
     * 저장 결과의 값이 null이 된다(`QaLogService`·`LlmUsageService`와 같은 이유).
     */
    @Column("created_at")
    val createdAt: Instant? = null,
)

/**
 * [KnowledgeEventEntity.event]가 가질 수 있는 값. V26의 CHECK 제약과 같은 집합이다.
 *
 * `RESTORE`는 **이번 범위에서 쓰는 곳이 없다.** `KnowledgeService`에 복원 경로 자체가 없고
 * (생성/수정/소프트삭제뿐) 복원 기능 추가는 범위 밖이다. 나중에 붙을 때 CHECK와 이 enum을
 * 함께 갈지 않도록 값만 열어 둔다.
 */
enum class KnowledgeEventType {
    CREATE,
    UPDATE,
    DELETE,
    RESTORE,
}
