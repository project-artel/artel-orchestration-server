package kr.artel.orchestration.knowledge.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 지식 한 항목을 씬·화면에 묶는 앵커(ARTEL-591)의 R2DBC 엔티티.
 *
 * **knowledge 의 컬럼이 아니라 별도 행인 이유**는 한 지식이 여러 화면에 정당하게 걸리기
 * 때문이다("전투 중 ESC 는 아무것도 하지 않는다"는 전투 화면 셋에 걸린다). 컬럼이면 첫 화면만
 * 남고 나머지는 소리 없이 사라진다.
 *
 * **앵커가 없는 지식은 게임 전체의 사실이다.** 그것이 기본값이고, 그래서 이 표는 비어 있는 것이
 * 정상 상태다.
 *
 * [knowledgeId] 와 [screenId] 는 둘 다 논리참조라 FK 가 없다(V55). knowledge 가 project·document·
 * run 을 논리참조로 드는 관례(V13, V19, V28)와 같고, 특히 `content_map.screen` 에 하드 FK 를
 * 걸면 게임 빌드 삭제가 지식 삭제로 번진다.
 */
@Table("knowledge_anchor")
data class KnowledgeAnchorEntity(
    @Id
    val id: Long? = null,

    @Column("knowledge_id")
    val knowledgeId: Long,

    /**
     * 게임이 부르는 씬 이름. 화면은 씬 안에 살고 씬 이름은 게임 상태 프레임이 매번 실어 주므로
     * 앵커를 달 수 있는 시점이면 언제나 안다 — 그래서 NOT NULL 이다.
     *
     * content map 과 대조하지 않는다. content map 이 없는 프로젝트도 씬 이름은 있고, 검증하면 그
     * 프로젝트에서 오는 앵커가 전부 거절된다.
     */
    @Column("scene_name")
    val sceneName: String,

    /**
     * 판정된 화면(`content_map.screen.id`). null 이면 씬까지만 아는 앵커다.
     *
     * 화면은 pulse 관측으로 판정되는 것이라(V40) 판정이 안 되는 순간이 정상적으로 있다. 그때
     * 화면을 지어내는 것보다 모른다고 하는 편이 낫다.
     */
    @Column("screen_id")
    val screenId: Long? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
