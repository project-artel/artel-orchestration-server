package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * capability_proof — 결론에 이르는 사슬. **한 단계 = 한 행.**
 *
 * 등급 하나([CapabilityEvidenceEntity.analysisConfidence])로는 "이 결론이 틀렸다"까지만 말할 수
 * 있다. 호출을 잘못 따라간 것인지 · 필드 쓰기를 잘못 읽은 것인지 · 조건을 잘못 붙인 것인지 가릴 수
 * 없어, **적재기 규칙을 고치러 갈 자리를 못 짚는다.**
 *
 * 등급 하나로 눌리면 신뢰도의 원인도 사라진다. 사슬의 한 단계만 흐린 탓에 전체가 내려간 것과,
 * 처음부터 끝까지 흐릿한 것이 같은 `derived` 로 보인다.
 *
 * JSONB 한 칸에 넣지 않는 이유: "어느 단계에서 흐려졌나"를 묻는 질의가 이 표의 존재 이유인데,
 * JSONB 에 넣으면 그 질의가 매번 문서를 펴야 한다.
 */
@Table("capability_proof")
data class CapabilityProofEntity(
    @Id
    val id: Long? = null,

    @Column("capability_id")
    val capabilityId: Long,

    /**
     * 어느 효과를 유도한 사슬인가.
     *
     * null 이면 기능 자체(`given` 을 세운 과정)에 붙은 사슬이다.
     */
    @Column("effect_id")
    val effectId: Long? = null,

    /** 사슬 안의 순서. `0` 부터. */
    @Column("seq")
    val seq: Int,

    /** 이 단계의 출발점. */
    @Column("source")
    val source: String,

    /** 무엇을 따라갔나 — `calls` · `writes` · `reads` 같은 관계 이름. */
    @Column("relation")
    val relation: String,

    /** 닿은 곳. 못 닿았으면 null 이고, 그때 [resolution] 은 보통 `unresolved` 다. */
    @Column("target")
    val target: String? = null,

    /** [AnalysisConfidence] 중 하나. 이 **단계** 의 확실성이다. */
    @Column("resolution")
    val resolution: String,

    /**
     * 적용한 규칙의 이름.
     *
     * 같은 규칙이 계속 흐린 결론을 내면 그 이름이 뭉쳐 나오고, **그것이 고칠 규칙이다.**
     */
    @Column("rule")
    val rule: String,
)
