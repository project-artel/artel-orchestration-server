package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.SceneObjectEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** `scene` 에 놓인 오브젝트(ARTEL-1059). */
interface SceneObjectRepository : CoroutineCrudRepository<SceneObjectEntity, Long> {

    /**
     * 지도 하나의 오브젝트 전부. id 순서가 곧 문서 순서다 — 적재가 문서 순서대로 넣는다.
     *
     * 순서를 고정하는 이유는 `scene-context` 의 다른 목록과 같다. 이 목록도 프롬프트에 실려 캐시를 탄다.
     */
    fun findByContentMapIdOrderByIdAsc(contentMapId: Long): Flow<SceneObjectEntity>

    suspend fun existsByContentMapId(contentMapId: Long): Boolean

    /** 재적재는 지우고 다시 넣는다. 안정 키가 없고, `scene` 에서 사라진 오브젝트를 남기면 없는 것을 그린다. */
    @Modifying
    @Query("DELETE FROM scene_object WHERE content_map_id = :contentMapId")
    suspend fun deleteByContentMapId(contentMapId: Long)
}
