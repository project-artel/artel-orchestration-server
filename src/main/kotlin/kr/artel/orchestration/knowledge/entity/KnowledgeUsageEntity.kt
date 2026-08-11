package kr.artel.orchestration.knowledge.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 검색이 QA 런에 내보낸 knowledge 히트 한 건(ARTEL-255).
 *
 * 검색을 Orchestration이 직접 수행하고 `qaTryId`도 이미 알고 있으므로(`QaAgentInboundRouter`),
 * 이 기록에 Agent를 건드릴 필요가 없다. 검색 한 번의 히트 전부가 여기에 한 줄씩 남는다.
 *
 * @property knowledgeVersion 검색이 내보낸 **시점의** 버전. 나중에 그 항목이 고쳐져도
 *   "그때 이 런이 읽은 것"은 이 값이다. 집계가 `(knowledge_id, version)`으로 붙는 근거다.
 * @property step 검색이 난 시점의 런 스텝. Agent가 payload에 실어 주면 채워지고(ARTEL-293),
 *   싣지 않는 런에서는 null이다. **인용 매칭의 조인 키가 아니라 기록되는 메타데이터다** —
 *   2번 스텝에서 검색한 것을 3번 스텝에서 인용하는 것은 정상 동작이고, step을 키로 삼으면
 *   그 인용이 어디에도 안 찍혀 증발한다.
 * @property rank 이 검색 안에서의 순위(1부터).
 * @property score 코사인 유사도(1에 가까울수록 가깝다). 응답에 실려 나간 값과 같다.
 * @property retrievalKind 이 행이 어느 경로로 런에 들어갔는지([KnowledgeRetrievalKind]).
 *   null은 이 컬럼 이전에 기록된 행이며 **출처를 모른다**는 뜻이다 — DIRECT가 아니다.
 * @property cited **nullable이고 기본값이 없다. 뜻이 셋이다.**
 *   - `null` — 이 런은 인용을 보고할 수단이 없었다
 *   - `false` — 보고 가능했는데 인용하지 않았다(qa_try가 종단으로 갈 때 확정된다)
 *   - `true` — 인용했다
 *
 *   `false`를 기본값으로 두면 인용 기능이 붙기 전 런 전부가 "아무도 안 쓴 지식"으로 보이고,
 *   그 오류는 조용히 지나간다.
 *
 *   ⚠️ **자기신고다.** `knowledge_event`(관측)와 성격이 다르다 — 모델이 빠뜨리므로 과소보고
 *   방향으로 치우친다. 안전한 방향이지만, 인용률로 모델을 줄 세울 때 "정직도" 차이가 섞인다.
 *
 *   ⚠️ **비율은 행이 아니라 (런, 항목) 단위로 센다.** 한 항목이 한 런에서 여러 번 검색됐으면
 *   그 행들이 모두 true가 된다. 묻는 것은 "이 런에서 썼나"이지 "몇 번 썼나"가 아니다.
 */
@Table("knowledge_usage")
data class KnowledgeUsageEntity(
    @Id
    val id: Long? = null,

    @Column("qa_try_id")
    val qaTryId: Long,

    @Column("knowledge_id")
    val knowledgeId: Long,

    @Column("knowledge_version")
    val knowledgeVersion: Int,

    @Column("step")
    val step: Int? = null,

    @Column("rank")
    val rank: Int? = null,

    @Column("score")
    val score: Float? = null,

    /**
     * [KnowledgeRetrievalKind]의 이름. 다른 제약 컬럼들과 같이 String으로 든다
     * (`knowledge.tag`, `qa_try.status`, `knowledge_edge.relation`) — 이 리포에는 R2DBC enum
     * 컨버터가 없어서, 여기만 enum 타입으로 두면 읽기 경로에 컨버터 설정이 하나 딸려 온다.
     * 값을 만드는 쪽은 enum이므로 오타는 그 자리에서 막힌다.
     */
    @Column("retrieval_kind")
    val retrievalKind: String? = null,

    @Column("cited")
    val cited: Boolean? = null,

    /** 컬럼 기본값이 아니라 `Clock`으로 stamp한다([KnowledgeEventEntity.createdAt]과 같은 이유). */
    @Column("retrieved_at")
    val retrievedAt: Instant? = null,
)
