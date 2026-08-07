package kr.artel.orchestration.knowledge.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * knowledge 항목 둘 사이의 관계 한 줄(ARTEL-274).
 *
 * **끝점은 정규 id(canonical id)다.** 스코프 런이 baseline을 고치면 그림자 행이 생기고(ARTEL-256)
 * 그 스코프의 검색은 그림자의 id를 낸다 — 에이전트가 쥔 id가 그림자 id일 수 있다는 뜻이다.
 * 그대로 저장하면 baseline 그래프와 스코프 그래프가 id 공간에서 갈라지므로,
 * 서비스가 `COALESCE(shadows_id, id)`로 접어 저장한다.
 *
 * `updated_at`이 없다. edge에는 수정이 없기 때문이다 — 고칠 수 있는 것은 [note]뿐이고 그것은
 * unlink 후 re-link로 되며, 끝점과 [relation]은 정체성이라 바꾸면 다른 edge다.
 */
@Table("knowledge_edge")
data class KnowledgeEdgeEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    /**
     * 이 edge가 속한 지식 스코프(ARTEL-256). null이면 운영 공용(baseline)이다.
     * 의미는 [KnowledgeScope]와 완전히 같다 — 실험 arm이 주장한 관계가 운영 그래프에 섞이면
     * 되돌릴 방법이 없다.
     */
    @Column("scope_id")
    val scopeId: Long? = null,

    @Column("from_knowledge_id")
    val fromKnowledgeId: Long,

    @Column("to_knowledge_id")
    val toKnowledgeId: Long,

    @Column("relation")
    val relation: String,

    /**
     * 이 관계를 주장한 이유. [KnowledgeRelation.LEADS_TO]만은 "왜"가 아니라 **무엇을 했는지**를
     * 진다("마을 상단바의 상점 버튼") — 경로를 나중 런이 쓸 수 있게 만드는 것이 그 문장이다.
     *
     * 빈 문자열을 허용하지 않는다(DB도 NOT NULL이다). 감사할 수 없는 edge는 아무도 확신을 갖고
     * 지울 수 없다.
     */
    @Column("note")
    val note: String,

    /**
     * 이 스코프 행이 가리는 baseline edge의 id(ARTEL-256 관례). null이면 그림자가 아니다.
     *
     * **값이 있으면 항상 툼스톤이다.** knowledge의 그림자는 수정과 삭제 둘 다를 지지만 edge는
     * 삭제만이다 — 고칠 것이 [note]뿐이고 그것은 unlink 후 re-link로 되기 때문이다.
     * DB의 `ck_knowledge_edge_tombstone`이 그 불변식을 건다(스코프 행일 것 + [deletedAt]이 있을 것).
     */
    @Column("shadows_edge_id")
    val shadowsEdgeId: Long? = null,

    @Column("created_by_qa_try_id")
    val createdByQaTryId: Long? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    /** 소프트삭제 표식. null이면 살아 있는 관계다. knowledge의 판단(ARTEL-188)을 그대로 따른다. */
    @Column("deleted_at")
    val deletedAt: Instant? = null,

    /** 되살려도 지우지 않는다 — "직전에 누가 지웠었나"가 곧 감사 기록이다(V19와 같은 판단). */
    @Column("deleted_by_qa_try_id")
    val deletedByQaTryId: Long? = null,
)

/**
 * edge의 스코프 가시성 술어. knowledge_edge를 읽는 **모든** 질의가 이 조각 하나를 쓴다.
 *
 * [KnowledgeScopeSql]이 존재하는 이유와 정확히 같다 — 술어를 각 질의에 손으로 적으면 언젠가
 * 한 곳이 빠지고, 빠진 격리는 조용히 틀린 결과를 낸다.
 */
object KnowledgeEdgeScopeSql {

    /**
     * `e` 별칭의 knowledge_edge 행이 `:scopeId` 스코프에서 보이는가.
     *
     * 세 조건이다.
     *
     * 1. **`e.scope_id IS NULL OR e.scope_id = :scopeId`** — baseline이거나 내 스코프의 것.
     *    `:scopeId`가 NULL(운영 런)이면 `= NULL`은 참이 될 수 없어 자동으로 baseline만 남는다.
     *
     * 2. **`e.deleted_at IS NULL`** — 여기가 [KnowledgeScopeSql.VISIBLE]과 다른 점이다. 거기서는
     *    그림자 자신이 결과에 남을지를 각 질의가 정해야 했다(수정본은 남고 툼스톤은 빠진다).
     *    edge에는 수정 그림자가 없어 **죽은 edge가 결과에 남아야 할 경우가 없으므로** 술어 안에
     *    넣는 편이 안전하다.
     *
     * 3. **툼스톤에 가려진 baseline 제외** — 이 술어에서 제일 틀리기 쉬운 자리다. 빠뜨리면
     *    스코프 런이 자기가 거둔 관계를 계속 돌려받는다. 운영 런에서는 `te.scope_id = NULL`이
     *    참이 될 수 없어 `NOT EXISTS`가 항상 참이고, 즉 이 절은 운영 런의 결과를 바꾸지 않는다.
     *
     * ⚠️ **통계 질의는 이 상수를 쓰지 않는다.** "수리 vs 폐기"는 지워진 edge도 세어야 한다 —
     * 같은 사실을 다른 필터로 읽는 소비처가 둘이라는 것이 edge를 컬럼이 아니라 행으로 둔 이득이다.
     */
    const val VISIBLE = """
        (e.scope_id IS NULL OR e.scope_id = :scopeId)
        AND e.deleted_at IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM knowledge_edge te
             WHERE te.scope_id = :scopeId
               AND te.shadows_edge_id = e.id
        )
    """
}
