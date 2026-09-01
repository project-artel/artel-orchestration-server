package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.SceneObjectRefEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 직렬화 참조 조회(ARTEL-615). 효과 대상을 사람이 찾을 수 있는 이름으로 옮기는 데 쓴다.
 */
interface SceneObjectRefRepository : CoroutineCrudRepository<SceneObjectRefEntity, Long> {

    fun findByContentMapId(contentMapId: Long): Flow<SceneObjectRefEntity>

    /**
     * 이 지도의 참조를 지운다. 재적재는 **지우고 다시 넣는다** — 안정 키가 없고, 씬에서 사라진
     * 참조를 남겨 두면 없는 오브젝트 이름이 기대결과에 실린다.
     */
    @Modifying
    @Query("DELETE FROM scene_object_ref WHERE content_map_id = :contentMapId")
    suspend fun deleteByContentMapId(contentMapId: Long)
}
