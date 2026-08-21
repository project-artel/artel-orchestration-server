package kr.artel.orchestration.testscenario.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 케이스가 **무엇으로 이루어져 있는가**를 지도에서 찾는 조회(ARTEL-466). **읽기 전용이다.**
 *
 * [ScenarioPathRepository]와 나눠 둔 것은 묻는 것이 다르기 때문이다. 그쪽은 "이 값을 누가 쓰나"고,
 * 여기는 "이 케이스가 가리키는 코드가 어느 기능인가"다.
 */
interface ScenarioCaseFactRepository : CoroutineCrudRepository<CapabilityEntity, Long> {

    /**
     * 근거 키가 가리키는 기능. 케이스의 `metadata.source.evidence` 와 지도의
     * `capability_evidence.method_id`·`entry_id` 를 잇는다.
     *
     * **꼬리로 맞춘다.** 케이스는 `WordVenture.Map.MapMove` 로, 지도는 `Map.MapMove` 로 같은 타입을
     * 부른다(네임스페이스 접두가 다르다). 그래서 부르는 쪽이 `%Map.MapMove|CharacterMove|System.Void()`
     * 같은 꼬리 패턴을 만들어 넘긴다 — 오프셋(`@79`)도 그쪽에서 뗀다.
     *
     * 이 축으로 닿는 케이스는 실측 66건 중 13건이다. 지도에 아직 그 기능이 없는 것이 대부분이라
     * **못 닿는 것이 정상**이고, 못 닿았다는 사실 자체가 답이 된다(지도 커버리지 구멍).
     */
    @Query(
        """
        SELECT c.* FROM capability c
        JOIN capability_evidence e ON e.capability_id = c.id
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId
          AND c.merged_into IS NULL
          AND (e.method_id LIKE :tail OR e.entry_id LIKE :tail)
        ORDER BY c.id
        """
    )
    fun findByEvidenceTail(contentMapId: Long, tail: String): Flow<CapabilityEntity>

    /**
     * 그 값을 건드리는 기능. 케이스의 `metadata.source.supporting_state`
     * (`` `MapMove.position` write `+1` ``)에서 뽑은 변수로 찾는다.
     *
     * 근거 키보다 넓게 닿는다(실측 22건). 대신 정확도가 낮다 — 같은 변수를 쓰는 기능이 여럿이면
     * 전부 나온다. 그래서 부르는 쪽이 이 축으로 찾은 것과 근거 키로 찾은 것을 **구분해서** 낸다.
     */
    @Query(
        """
        SELECT DISTINCT c.* FROM capability c
        JOIN capability_effect e ON e.capability_id = c.id
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId
          AND c.merged_into IS NULL
          AND (lower(e.target) = lower(:variable) OR lower(e.target) LIKE '%.' || lower(:variable))
        ORDER BY c.id
        """
    )
    fun findByEffectTarget(contentMapId: Long, variable: String): Flow<CapabilityEntity>
}
