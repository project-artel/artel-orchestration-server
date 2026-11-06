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
     * **화면을 넘어도 살아남는 값**의 이름들(ARTEL-654).
     *
     * 지도는 저장되는 값을 `saved` 로 적어 둔다. 그 전까지 경로 계산은 화면이 바뀌면 알던 값을
     * 전부 버렸다 — 무엇이 유지되는지 명세가 말해 주지 않는다고 보았기 때문인데, **말해 주는
     * 것이 있었다.**
     *
     * 다 버린 대가가 실측(런 220·221)에 나왔다. 진행도가 5인 채로 화면을 나온 뒤 "5가 아니어야
     * 한다"를 놓았는데 "아무것도 필요 없다"가 나왔고, 저작이 서로 부정하는 두 자리를 나란히
     * 담았다. 모르는 값은 그 값을 쓰는 조작이 하나라도 있으면 만들 수 있다고 읽히기 때문이다.
     *
     * 이름은 마지막 마디로 돌려준다 — 비교의 왼쪽도 그렇게 읽는다.
     */
    @Query(
        """
        SELECT DISTINCT regexp_replace(e.target, '^.*\.', '') AS value
        FROM capability_effect e
        JOIN capability c ON c.id = e.capability_id
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId
          AND c.merged_into IS NULL
          AND e.kind = 'saved'
          AND e.target IS NOT NULL
        """
    )
    fun findValuesThatSurviveScenes(contentMapId: Long): Flow<String>
}
