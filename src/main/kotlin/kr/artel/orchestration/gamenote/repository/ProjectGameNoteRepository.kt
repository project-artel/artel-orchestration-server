package kr.artel.orchestration.gamenote.repository

import kr.artel.orchestration.gamenote.entity.ProjectGameNoteEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * PK 가 (`project_id`, `version`) 라 `save()` 로 쓸 수 없다. 이유와 형식상의 id 타입은
 * `ScreenCapabilityRepository` 와 같다.
 */
interface ProjectGameNoteRepository : CoroutineCrudRepository<ProjectGameNoteEntity, Long> {

    @Query("SELECT * FROM project_game_note WHERE project_id = :projectId ORDER BY version DESC LIMIT 1")
    suspend fun findLatest(projectId: Long): ProjectGameNoteEntity?

    /**
     * [version] 이 비어 있으면 넣고 그 행을, 이미 있으면 null 을 돌려준다. null 은 같은 버전을 다른 저장이 먼저
     * 넣었다는 뜻이다.
     *
     * [genres] 를 `List` 가 아니라 배열로 받는 이유는 Spring Data 가 `@Query` 의 컬렉션 인자를 `IN` 목록으로
     * 펼치기 때문이다. 배열이어야 `text[]` 값 하나로 묶인다.
     */
    @Query(
        """
        INSERT INTO project_game_note (project_id, version, body, genres, created_by, created_at)
        VALUES (:projectId, :version, :body, :genres, :createdBy, :createdAt)
        ON CONFLICT (project_id, version) DO NOTHING
        RETURNING *
        """
    )
    suspend fun insertIfAbsent(
        projectId: Long,
        version: Int,
        body: String,
        genres: Array<String>,
        createdBy: Long,
        createdAt: Instant
    ): ProjectGameNoteEntity?
}
