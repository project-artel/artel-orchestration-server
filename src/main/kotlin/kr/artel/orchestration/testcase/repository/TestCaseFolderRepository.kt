package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.TestCaseFolderRow
import kr.artel.orchestration.testcase.entity.TestCaseFolderEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface TestCaseFolderRepository : CoroutineCrudRepository<TestCaseFolderEntity, Long> {

    /**
     * folder 목록과 folder 마다 담긴 케이스 수(ARTEL-1023).
     *
     * `visible_count` 는 [gameBuildId] 버전에서 보이는 항목 수다 — 그 버전에서 숨기지 않았고, 그 버전에 있는
     * 케이스(지도 케이스는 버전 몫이 있는 것, 사람이 쓴 케이스는 전부). [gameBuildId] 가 null 이면 null 이다.
     */
    @Query(
        """
        SELECT f.id, f.parent_id, f.name,
               COUNT(i.id) AS case_count,
               CASE WHEN :gameBuildId IS NULL THEN NULL ELSE
                   COUNT(i.id) FILTER (
                       WHERE NOT (CAST(:gameBuildId AS BIGINT) = ANY (i.hidden_game_build_ids))
                         AND (t.metadata ->> 'origin' IS DISTINCT FROM 'content-map'
                              OR EXISTS (SELECT 1 FROM test_case_version v
                                          WHERE v.test_case_id = i.test_case_id
                                            AND v.game_build_id = CAST(:gameBuildId AS BIGINT)))
                   )
               END AS visible_count
        FROM test_case_folder f
        LEFT JOIN test_case_folder_item i ON i.folder_id = f.id
        LEFT JOIN test_case t ON t.id = i.test_case_id
        WHERE f.project_id = :projectId
        GROUP BY f.id
        ORDER BY f.id
        """
    )
    fun findRowsByProjectId(projectId: Long, gameBuildId: Long?): Flow<TestCaseFolderRow>

    @Query(
        """
        SELECT f.id, f.parent_id, f.name, COUNT(i.id) AS case_count, NULL::BIGINT AS visible_count
        FROM test_case_folder f
        LEFT JOIN test_case_folder_item i ON i.folder_id = f.id
        WHERE f.id = :id
        GROUP BY f.id
        """
    )
    suspend fun findRowById(id: Long): TestCaseFolderRow?

    /** [parentId] 의 바로 아래 folder 들. 복제가 하위 트리를 따라 내려갈 때 읽는다. */
    fun findByParentIdOrderByIdAsc(parentId: Long): Flow<TestCaseFolderEntity>

    suspend fun findByIdAndProjectId(id: Long, projectId: Long): TestCaseFolderEntity?

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
