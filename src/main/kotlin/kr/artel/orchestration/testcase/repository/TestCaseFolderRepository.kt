package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.TestCaseFolderRow
import kr.artel.orchestration.testcase.entity.TestCaseFolderEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface TestCaseFolderRepository : CoroutineCrudRepository<TestCaseFolderEntity, Long> {

    @Query(
        """
        SELECT f.id, f.parent_id, f.name, f.content_map_id, cm.game_build_id
        FROM test_case_folder f
        LEFT JOIN content_map cm ON cm.id = f.content_map_id
        WHERE f.project_id = :projectId
        ORDER BY f.id
        """
    )
    fun findRowsByProjectId(projectId: Long): Flow<TestCaseFolderRow>

    @Query(
        """
        SELECT f.id, f.parent_id, f.name, f.content_map_id, cm.game_build_id
        FROM test_case_folder f
        LEFT JOIN content_map cm ON cm.id = f.content_map_id
        WHERE f.id = :id
        """
    )
    suspend fun findRowById(id: Long): TestCaseFolderRow?

    suspend fun findByIdAndProjectId(id: Long, projectId: Long): TestCaseFolderEntity?

    /** content map 에서 test case 를 생성할 때 만든 folder(ARTEL-971). content map 하나에 하나다(V99 UNIQUE). */
    suspend fun findByContentMapId(contentMapId: Long): TestCaseFolderEntity?

    /**
     * 같은 부모 아래 [name] 을 쓰는 다른 folder 가 있나. `IS NOT DISTINCT FROM` 이라 [parentId] 가 null 이면
     * 최상위 folder 끼리 비교한다 — `=` 로 쓰면 null 과 null 이 같지 않아 늘 거짓이다.
     */
    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM test_case_folder
            WHERE project_id = :projectId
              AND parent_id IS NOT DISTINCT FROM :parentId
              AND name = :name
              AND id IS DISTINCT FROM :excludeId
        )
        """
    )
    suspend fun existsSiblingName(projectId: Long, parentId: Long?, name: String, excludeId: Long?): Boolean

    /** [candidateId] 가 [folderId] 자신이거나 그 하위 folder 인가. folder 를 그 아래로 옮기면 순환이 된다. */
    @Query(
        """
        WITH RECURSIVE subtree(id) AS (
            SELECT id FROM test_case_folder WHERE id = :folderId
            UNION ALL
            SELECT child.id FROM test_case_folder child JOIN subtree ON child.parent_id = subtree.id
        )
        SELECT EXISTS (SELECT 1 FROM subtree WHERE id = :candidateId)
        """
    )
    suspend fun isSelfOrDescendant(folderId: Long, candidateId: Long): Boolean
}
