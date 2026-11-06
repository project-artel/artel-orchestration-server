package kr.artel.orchestration.knowledge.entity

/**
 * 지식창고를 가르는 스코프(ARTEL-256). 한 QA 런이 무엇을 읽고 어디에 쓰는지를 정한다.
 *
 * - [PRODUCTION] (`id == null`): 운영 공용. 지금까지의 모든 지식이 여기 있고, 운영 런은 이것만
 *   읽고 여기에만 쓴다. 이 스코프의 동작은 ARTEL-256 이전과 완전히 동일하다.
 * - 스코프 런 (`id != null`): **baseline + 자기 것**을 읽고, 쓰기는 전부 자기 스코프로 간다.
 *   운영 지식창고는 이 런 때문에 한 행도 바뀌지 않는다.
 *
 * **`Long?` 대신 타입을 만든 이유.** 읽기 경로를 하나라도 빠뜨리면 격리가 뚫리는데, 뚫린 격리는
 * 조용하다 — 결과가 그럴듯해서 아무도 못 알아챈다. 스코프를 기본값 없는 이 타입으로 받으면
 * 빠뜨린 호출은 컴파일되지 않고, `Long?`처럼 옆에 있는 projectId를 실수로 넘길 수도 없다.
 * NULL의 의미도 이름([PRODUCTION])으로 고정된다.
 *
 * @param id 스코프 식별자. 지금은 `qa_try.knowledge_scope_id`가 곧 이 값이다. 실험 엔티티가 생기면
 *   그 arm의 id가 여기 들어오게 되지만, 이 타입과 질의는 그대로다.
 */
@JvmInline
value class KnowledgeScope private constructor(val id: Long?) {

    /** 이 스코프가 쓰는 지식이 운영 지식창고로 가는가. */
    val isProduction: Boolean get() = id == null

    override fun toString(): String = id?.toString() ?: "PRODUCTION"

    companion object {
        /** 운영 공용 스코프(baseline). `scope_id IS NULL`인 행들. */
        val PRODUCTION = KnowledgeScope(null)

        fun of(id: Long?): KnowledgeScope = if (id == null) PRODUCTION else KnowledgeScope(id)
    }
}

/**
 * 스코프 가시성 술어. knowledge를 읽는 **모든** 질의가 이 조각 하나를 쓴다.
 *
 * 술어를 각 질의에 손으로 적으면 언젠가 한 곳이 빠지고, 빠진 격리는 조용히 틀린 결과를 낸다.
 * 그래서 파생 쿼리(`findByProjectId...`)를 없애고 SQL을 여기로 모았다.
 */
object KnowledgeScopeSql {

    /**
     * `k` 별칭의 knowledge 행이 `:scopeId` 스코프에서 보이는가.
     *
     * 두 조건이다.
     *
     * 1. **`k.scope_id IS NULL OR k.scope_id = :scopeId`** — baseline이거나 내 스코프의 것.
     *    `:scopeId`가 NULL(운영 런)이면 `= NULL`은 참이 될 수 없으므로 자동으로 baseline만 남는다.
     *    운영 런에 대한 별도 분기가 필요 없는 것은 이 성질 덕이다.
     *
     * 2. **그림자에 가려진 baseline 제외** — 이 술어에서 제일 틀리기 쉬운 자리다. 빠뜨리면
     *    스코프 런이 자기가 지운 항목을 계속 돌려받고(툼스톤이 baseline을 못 가림), 고친 항목은
     *    원본과 수정본이 **둘 다** 나온다.
     *
     *    수정 그림자든 툼스톤이든 baseline을 가리는 것은 같다. 그래서 `sh.deleted_at`을 보지
     *    않는다 — 툼스톤에서만 걸러내면 삭제가 삭제 노릇을 못 한다. 그림자 자신이 결과에
     *    남을지는 각 질의의 `deleted_at IS NULL` 필터가 이미 정한다(수정본은 남고 툼스톤은 빠진다).
     *
     *    운영 런에서는 `sh.scope_id = NULL`이 참이 될 수 없어 `NOT EXISTS`가 항상 참이다.
     *    즉 이 절은 운영 런의 결과를 바꾸지 않는다.
     */
    const val VISIBLE = """
        (k.scope_id IS NULL OR k.scope_id = :scopeId)
        AND NOT EXISTS (
            SELECT 1 FROM knowledge sh
             WHERE sh.scope_id = :scopeId
               AND sh.shadows_id = k.id
        )
    """
}
