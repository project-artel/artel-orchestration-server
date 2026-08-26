package kr.artel.orchestration.testscenario.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 경로 조회가 씬 명세에 던지는 질문 하나(ARTEL-466). **읽기 전용이다.**
 *
 * 기존 [kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository] 를 쓰지 않는 이유는
 * 이름을 맞추는 방식이 다르기 때문이다. 그쪽은 `target` 을 있는 그대로 찾고, 여기서는 **마지막
 * 마디로** 찾아야 한다 — 사전조건은 `MapMove.position` 을 `position` 으로도 쓰고 명세는
 * `StageDataSingleton.stagePosition` 처럼 다른 소유자를 앞에 붙인다. 그 규칙을 남의 리포지토리에
 * 밀어 넣으면 그쪽 계약이 이 사정에 끌려간다.
 */
interface ScenarioPathRepository : CoroutineCrudRepository<CapabilityEffectEntity, Long> {

    /**
     * 이 지도에서 그 변수를 쓰는 기능의 효과들.
     *
     * **마지막 마디로 맞추고 대소문자를 무시한다.** 같은 값을 명세가
     * `StagePosition` · `MapMove.StagePosition` · `StageDataSingleton.stagePosition` 세 이름으로
     * 부르는 것이 실제로 관측됐다. 마디가 겹치는 서로 다른 변수(`collision.tag` 와
     * `combineZone.tag`)를 함께 집을 수 있다는 것은 **알려진 한계**이고, 근본 해결은 명세가
     * 별칭을 declare 하는 것이다.
     *
     * `merged_into` 가 찍힌 기능은 뺀다 — 관측으로 발견한 것이 나중에 근거로도 확인되면
     * 한쪽으로 합쳐지고, 합쳐진 쪽을 답으로 내면 없는 기능을 가리키게 된다.
     */
    @Query(
        """
        SELECT e.* FROM capability_effect e
        JOIN capability c ON c.id = e.capability_id
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId
          AND c.merged_into IS NULL
          AND (lower(e.target) = lower(:variable)
               OR lower(e.target) LIKE '%.' || lower(:variable))
        ORDER BY e.id
        """
    )
    fun findEffectsWriting(contentMapId: Long, variable: String): Flow<CapabilityEffectEntity>

    /**
     * 이 기능이 **어느 화면으로 넘기나**(ARTEL-528). `kind='scene'` 효과의 `target` 이 목적지다.
     *
     * 케이스가 끝난 뒤 어느 화면인지를 **계산으로** 답하는 유일한 길이다. 지도가 그 기능을 알고
     * 있으면 산문을 읽을 필요가 없다 — 실측에서 지도가 아는 2건은 정확히 맞혔다.
     */
    @Query(
        """
        SELECT e.* FROM capability_effect e
        WHERE e.capability_id = :capabilityId AND e.kind = 'scene'
        ORDER BY e.id
        """
    )
    fun findSceneEffects(capabilityId: Long): Flow<CapabilityEffectEntity>
}
