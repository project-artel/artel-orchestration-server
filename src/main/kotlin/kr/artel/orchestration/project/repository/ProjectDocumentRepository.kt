package kr.artel.orchestration.project.repository

import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface ProjectDocumentRepository : ReactiveCrudRepository<ProjectDocumentEntity, Long> {

    /** 최신 버전이 앞에 오도록 정렬해 반환한다. */
    fun findByProjectIdOrderByVersionDesc(projectId: Long): Flux<ProjectDocumentEntity>

    fun findFirstByProjectIdOrderByVersionDesc(projectId: Long): Mono<ProjectDocumentEntity>

    fun findByIdAndProjectId(id: Long, projectId: Long): Mono<ProjectDocumentEntity>

    /** 프로젝트 단위 파일 중복 검증용. 같은 프로젝트에 동일 content_hash가 이미 있는지. */
    fun existsByProjectIdAndContentHash(projectId: Long, contentHash: String): Mono<Boolean>

    fun countByProjectId(projectId: Long): Mono<Long>

    /**
     * 다음 버전을 채번하기 위한 현재 최대값. 읽은 뒤 쓰는 사이에 경합이 나므로 이 값만으로는
     * 충분하지 않고, (project_id, version) 유니크 제약과 재시도가 실제 방어선이다.
     */
    @Query("SELECT COALESCE(MAX(version), 0) FROM project_document WHERE project_id = :projectId")
    fun findMaxVersion(projectId: Long): Mono<Int>

    /**
     * 여러 프로젝트의 최신 기획서를 한 번에 가져온다. 목록 한 줄마다 따로 조회하면 N+1이 된다.
     *
     * PostgreSQL의 DISTINCT ON을 쓰면 짧아지지만 테스트가 H2에서 돌기 때문에 양쪽에서 동작하는
     * "그룹별 최대 버전과 다시 조인" 형태로 쓴다.
     */
    @Query(
        """
        SELECT d.* FROM project_document d
        JOIN (
            SELECT project_id, MAX(version) AS max_version
            FROM project_document
            WHERE project_id IN (:projectIds)
            GROUP BY project_id
        ) latest ON latest.project_id = d.project_id AND latest.max_version = d.version
        """
    )
    fun findLatestByProjectIds(projectIds: Collection<Long>): Flux<ProjectDocumentEntity>

    @Query(
        """
        SELECT project_id, COUNT(*) AS document_count
        FROM project_document
        WHERE project_id IN (:projectIds)
        GROUP BY project_id
        """
    )
    fun countByProjectIds(projectIds: Collection<Long>): Flux<ProjectDocumentCount>

    /**
     * 문서의 추출 진행 상태를 갱신한다. knowledge 적재가 끝나면 EXTRACTED로 올린다.
     * 존재하지 않는 id면 0행이 갱신되고 오류는 나지 않는다(추출 저장 자체를 막지 않는다).
     */
    @Modifying
    @Query("UPDATE project_document SET parse_status = :parseStatus WHERE id = :documentId")
    fun updateParseStatus(documentId: Long, parseStatus: String): Mono<Long>
}

data class ProjectDocumentCount(
    val projectId: Long,
    val documentCount: Long
)
