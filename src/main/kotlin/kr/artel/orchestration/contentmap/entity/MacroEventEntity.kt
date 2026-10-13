package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * macro 하나에 일어난 변경 한 건(V100). `knowledge_event` 와 같은 모양이다.
 *
 * `macro` 행 갱신과 이 event 삽입은 **같은 트랜잭션**이다. 쪼개지면 `macro.version` 과 이 표의
 * 최대 content 버전이 어긋난 채 남는다.
 *
 * content 세 칸([source] · [definitionJson] · [parameterNames])은 DELETE 에만 null 이다. 그 null 이
 * "이 event 는 버전을 만들지 않았다" 는 표시고, DELETE 의 [version] 은 지우기 직전 버전이다.
 *
 * @property qaTryId 이 변경을 일으킨 QA run 의 `qa_try`. 사람이 한 revert 와 V100 backfill 은 null.
 * @property revertsEventId 이 event 가 되돌린 event 의 id. revert 가 아니면 null.
 */
@Table("macro_event")
data class MacroEventEntity(
    @Id
    val id: Long? = null,

    @Column("macro_id")
    val macroId: Long,

    @Column("content_map_id")
    val contentMapId: Long,

    @Column("qa_try_id")
    val qaTryId: Long? = null,

    @Column("event")
    val event: String,

    @Column("version")
    val version: Int,

    /** DELETE event 만 남은 macro 도 이름이 보여야 하므로 모든 event 에 있다. */
    @Column("name")
    val name: String,

    @Column("source")
    val source: String? = null,

    @Column("definition_json")
    val definitionJson: Json? = null,

    @Column("parameter_names")
    val parameterNames: Json? = null,

    @Column("reverts_event_id")
    val revertsEventId: Long? = null,

    @Column("reverted_by_app_user_id")
    val revertedByAppUserId: Long? = null,

    /**
     * 컬럼 기본값에 맡기지 않고 `Clock` 으로 찍는다. R2DBC 가 insert 뒤 기본값을 다시 읽지 않아
     * 저장 결과의 값이 null 이 된다(`KnowledgeEventEntity.createdAt` 과 같은 이유).
     */
    @Column("created_at")
    val createdAt: Instant? = null,
)

/**
 * [MacroEventEntity.event] 가 가질 수 있는 값. V100 의 CHECK 와 같은 집합이다.
 *
 * `RESTORE` 는 쓰는 곳이 없다. QA run 이 macro 를 지우는 길이 없어 되살릴 일도 없다. CHECK 를 갈지
 * 않도록 `knowledge_event` 와 같은 값을 열어 둔다.
 */
enum class MacroEventType {
    CREATE,
    UPDATE,
    DELETE,
    RESTORE,
}
