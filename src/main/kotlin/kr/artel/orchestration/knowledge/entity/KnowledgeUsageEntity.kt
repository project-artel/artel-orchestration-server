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
 * @property step 런의 몇 번째 스텝이었나. **이번 범위에서는 항상 null** — `KnowledgeSearchRequest`가
 *   step을 싣지 않는다. 후속에서 Agent가 실으면 채워진다.
 * @property rank 이 검색 안에서의 순위(1부터).
 * @property score 코사인 유사도(1에 가까울수록 가깝다). 응답에 실려 나간 값과 같다.
 * @property cited **nullable이고 기본값이 없다. 뜻이 셋이다.**
 *   - `null` — 이 런은 인용을 보고할 수단이 없었다
 *   - `false` — 보고 가능했는데 인용하지 않았다
 *   - `true` — 인용했다
 *
 *   `false`를 기본값으로 두면 인용 기능이 붙기 전 런 전부가 "아무도 안 쓴 지식"으로 보이고,
 *   그 오류는 조용히 지나간다. 이번 범위에서 이 값은 항상 null이다.
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

    @Column("cited")
    val cited: Boolean? = null,

    /** 컬럼 기본값이 아니라 `Clock`으로 stamp한다([KnowledgeEventEntity.createdAt]과 같은 이유). */
    @Column("retrieved_at")
    val retrievedAt: Instant? = null,
)
