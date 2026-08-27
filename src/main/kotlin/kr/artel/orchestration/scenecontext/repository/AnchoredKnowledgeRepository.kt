package kr.artel.orchestration.scenecontext.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 한 프로젝트의 **`anchor` 가 달린 지식 전부**를 씬 이름과 함께 한 번에 읽는다(ARTEL-611).
 *
 * [KnowledgeAnchorRepository.findVisibleFor][kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository]
 * 와 묻는 질문이 다르다. 저쪽은 "이 히트들의 `anchor`"라 검색이 이미 고른 id 묶음으로 들어오고,
 * 이쪽은 "이 프로젝트에서 `anchor` 가 달린 것 전부"라 입력이 프로젝트 하나다. 씬마다 부르지 않는
 * 것이 이 조회의 존재 이유이므로, 씬 이름을 인자로 받지 않는다.
 *
 * **스코프 술어는 [KnowledgeScopeSql.VISIBLE] 하나를 그대로 지난다.** 손으로 다시 적으면
 * 언젠가 한 곳이 빠지고, 빠진 격리는 조용히 틀린 결과를 낸다 — 가려졌어야 할 지식이 `anchor` 째
 * 프롬프트에 실리는데, 그 프롬프트를 나중에 봐도 무엇이 새어 들어왔는지 알 수 없다.
 */
interface AnchoredKnowledgeRepository : CoroutineCrudRepository<KnowledgeAnchorEntity, Long> {

    /**
     * [projectId] 에서 [scopeId] 스코프에 보이는, **`anchor` 가 달린** 지식을 씬 이름과 함께 낸다.
     *
     * `DISTINCT` 인 이유: 한 지식이 같은 씬의 화면 둘에 걸리는 것이 정상이다(V55 — "전투 중
     * ESC 는 아무것도 하지 않는다"가 전투 화면 셋에 걸린다). 이 응답은 씬 단위라 화면을
     * 내리지 않으므로, 접지 않으면 같은 사실이 한 씬에서 여러 줄로 나온다.
     *
     * `description` 을 고르지 않는 것도 계약이다. 이 결과는 매 모델 호출마다 다시 그려지는
     * 프롬프트 블록으로 가고, 본문은 그 자리에서 감당할 수 없다.
     *
     * 소프트삭제된 지식은 뺀다 — 읽기 경로에서 사라진 지식의 `anchor` 만 남아 나오면 agent 가 이미
     * 지워진 사실을 계속 참으로 읽는다.
     *
     * 순서를 씬 이름·지식 id 로 고정하는 것은 취향이 아니다. 이 목록이 프롬프트에 실려 프롬프트
     * 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨진다.
     */
    @Query(
        """
        SELECT DISTINCT a.scene_name AS scene_name,
                        k.id         AS knowledge_id,
                        k.summary    AS summary
          FROM knowledge_anchor a
          JOIN knowledge k ON k.id = a.knowledge_id
         WHERE k.project_id = :projectId
           AND k.deleted_at IS NULL
           AND ${KnowledgeScopeSql.VISIBLE}
         ORDER BY scene_name ASC, knowledge_id ASC
        """
    )
    fun findAnchoredKnowledge(projectId: Long, scopeId: Long?): Flow<AnchoredKnowledgeRow>
}

/**
 * [AnchoredKnowledgeRepository.findAnchoredKnowledge] 한 줄. 씬 이름 + 지식 id + 요약이 전부다.
 *
 * 본문이 없는 것이 이 타입의 요점이다 — 담을 자리가 없으면 실수로 실릴 수도 없다.
 */
data class AnchoredKnowledgeRow(
    @Column("scene_name")
    val sceneName: String,

    @Column("knowledge_id")
    val knowledgeId: Long,

    @Column("summary")
    val summary: String,
)
