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
     * **하나를 가리키지 않는다.** 꼬리가 메서드 단위라 그 안에서 갈라진 기능을 전부 낸다 — 실측
     * (적재기 지도)에서 `Map.MapMove|CharacterMove|System.Void()` 하나가 기능 14개를 내고 그 안에
     * `LeftArrow` 와 `RightArrow` 가 섞여 있다. 좁히는 것은 부르는 쪽 몫이다(ARTEL-536).
     *
     * 손으로 넣은 골든 지도에서는 이 축이 **한 번도 닿지 않았다**(`capability_evidence` 0행).
     * 적재기가 만든 지도에서는 근거를 든 케이스 61건 중 55건이 닿는다.
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
     * **UI 조준 대상**이 가리키는 기능(ARTEL-537). 케이스의 `object:Canvas[2]/ExitButton[3]` 과
     * 지도의 `capability.control_selector` 를 잇는다.
     *
     * 꼬리 맞춤이 아니라 **같은 문자열**이다. 형제 인덱스까지 붙어 있어 한 판독 안에서는 오브젝트
     * 하나를 가리키므로 근거 키처럼 여럿으로 번지지 않는다. 대신 계층이 바뀌면 인덱스가 밀려
     * 같은 문자열이 다른 것을 가리킬 수 있다 — 같은 빌드 안에서만 믿을 수 있다.
     */
    @Query(
        """
        SELECT c.* FROM capability c
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId
          AND c.merged_into IS NULL
          AND c.control_selector = :selector
        ORDER BY c.id
        """
    )
    fun findByControlSelector(contentMapId: Long, selector: String): Flow<CapabilityEntity>

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
