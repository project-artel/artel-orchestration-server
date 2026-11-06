package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.ContentMapStrayEffectEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ContentMapStrayEffectRepository : CoroutineCrudRepository<ContentMapStrayEffectEntity, Long> {
    suspend fun deleteByContentMapId(contentMapId: Long): Long
    fun findByContentMapId(contentMapId: Long): Flow<ContentMapStrayEffectEntity>
}
