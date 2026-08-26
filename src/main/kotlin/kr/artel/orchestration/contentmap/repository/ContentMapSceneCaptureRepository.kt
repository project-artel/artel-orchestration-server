package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.ContentMapSceneCaptureEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ContentMapSceneCaptureRepository : CoroutineCrudRepository<ContentMapSceneCaptureEntity, Long> {
    fun findByDocumentIdOrderBySceneNameAsc(documentId: Long): Flow<ContentMapSceneCaptureEntity>

    @Modifying
    @Query("DELETE FROM content_map_scene_capture WHERE document_id = :documentId")
    suspend fun deleteByDocumentId(documentId: Long): Long
}
