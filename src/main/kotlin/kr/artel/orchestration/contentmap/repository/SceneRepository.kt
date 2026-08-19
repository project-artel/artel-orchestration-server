package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.SceneEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 씬 조회. 갱신 단위가 씬이므로 (content_map, name) 조회가 적재의 멱등 키다.
 */
interface SceneRepository : CoroutineCrudRepository<SceneEntity, Long> {

    suspend fun findByContentMapIdAndName(contentMapId: Long, name: String): SceneEntity?

    fun findByContentMapIdOrderByNameAsc(contentMapId: Long): Flow<SceneEntity>
}
