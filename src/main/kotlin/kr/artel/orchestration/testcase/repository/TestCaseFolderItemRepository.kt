package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.CaseFirstFolder
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemRow
import kr.artel.orchestration.testcase.entity.TestCaseFolderItemEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * folder 항목(ARTEL-1023). 쓰기는 모두 [kr.artel.orchestration.testcase.service.TestCaseFolderItemService] 가
 * 프로젝트 folder lock 을 잡은 트랜잭션 안에서 한다. folder 와 케이스가 그 프로젝트 것인지도 거기서 확인한다.
 *
 * 순서가 뜻이 있는 목록은 `,` 로 이은 문자열([order])로 따로 받아 `array_position` 으로 읽는다. `IN (:ids)` 의
 * 목록은 bind 변수 여러 개로 펼쳐지고, `ARRAY[:ids]` 는 Spring 의 이름 붙은 변수 해석이 대괄호 안을 건너뛰어 쓸 수
 * 없다.
 */
interface TestCaseFolderItemRepository : CoroutineCrudRepository<TestCaseFolderItemEntity, Long> {

    /** [folderId] 의 항목을 순서대로. [hidden] 은 [gameBuildId] 버전에서 숨겼는가이고, 버전이 null 이면 늘 false 다. */
    @Query(
        """
        SELECT i.test_case_id, i.position,
               COALESCE(CAST(:gameBuildId AS BIGINT) = ANY (i.hidden_game_build_ids), FALSE) AS hidden,
               array_to_string(i.hidden_game_build_ids, ',') AS hidden_game_build_ids
          FROM test_case_folder_item i
         WHERE i.folder_id = :folderId
         ORDER BY i.position, i.id
        """
    )
    fun findRows(folderId: Long, gameBuildId: Long?): Flow<TestCaseFolderItemRow>

    @Query("SELECT test_case_id FROM test_case_folder_item WHERE folder_id = :folderId ORDER BY position, id")
    fun findCaseIds(folderId: Long): Flow<Long>

    /**
     * [caseIds] 중 이 프로젝트 케이스이고 아직 [folderId] 에 없는 것을 끝에 붙인다. 요청한 순서를 지킨다.
     * 실제로 붙인 케이스 id 를 돌려준다 — home 이 Undo 할 때 그것만 뺀다.
     */
    @Query(
        """
        INSERT INTO test_case_folder_item (folder_id, test_case_id, position)
        SELECT :folderId, t.id,
               (SELECT COALESCE(MAX(position) + 1, 0) FROM test_case_folder_item WHERE folder_id = :folderId)
                 + ROW_NUMBER() OVER (ORDER BY array_position(string_to_array(:order, ',')::BIGINT[], t.id)) - 1
          FROM test_case t
         WHERE t.project_id = :projectId
           AND t.id IN (:caseIds)
           AND NOT EXISTS (SELECT 1 FROM test_case_folder_item i WHERE i.folder_id = :folderId AND i.test_case_id = t.id)
        RETURNING test_case_id
        """
    )
    fun append(projectId: Long, folderId: Long, caseIds: Collection<Long>, order: String = caseIds.joinToString(",")): Flow<Long>

    @Query("DELETE FROM test_case_folder_item WHERE folder_id = :folderId AND test_case_id IN (:caseIds) RETURNING test_case_id")
    fun remove(folderId: Long, caseIds: Collection<Long>): Flow<Long>

    /** [caseIds] 를 이 프로젝트의 모든 folder 에서 뺀다. 예전 단일 folder 옮기기(`/test-cases/move`)가 쓴다. */
    @Modifying
    @Query(
        """
        DELETE FROM test_case_folder_item i
         USING test_case t
         WHERE t.id = i.test_case_id AND t.project_id = :projectId AND i.test_case_id IN (:caseIds)
        """
    )
    suspend fun removeEverywhere(projectId: Long, caseIds: Collection<Long>): Int

    /**
     * [fromFolderId] 의 [caseIds] 항목을 [toFolderId] 끝으로 옮긴다. 숨김은 따라간다. 대상에 이미 있는 케이스는
     * 옮기지 않는다 — 부르는 쪽이 [remove] 로 이쪽 항목을 먼저 정리한다.
     */
    @Query(
        """
        UPDATE test_case_folder_item i
           SET folder_id = :toFolderId,
               position = r.base + r.rn
          FROM (SELECT s.id,
                       ROW_NUMBER() OVER (ORDER BY s.position, s.id) - 1 AS rn,
                       (SELECT COALESCE(MAX(position) + 1, 0) FROM test_case_folder_item WHERE folder_id = :toFolderId) AS base
                  FROM test_case_folder_item s
                 WHERE s.folder_id = :fromFolderId AND s.test_case_id IN (:caseIds)) r
         WHERE i.id = r.id
        RETURNING i.test_case_id
        """
    )
    fun move(fromFolderId: Long, toFolderId: Long, caseIds: Collection<Long>): Flow<Long>

    /** [folderId] 에도 [otherFolderId] 에도 있는 [caseIds]. 옮기기 전에 겹치는 것을 고른다. */
    @Query(
        """
        SELECT a.test_case_id FROM test_case_folder_item a
          JOIN test_case_folder_item b ON b.test_case_id = a.test_case_id AND b.folder_id = :otherFolderId
         WHERE a.folder_id = :folderId AND a.test_case_id IN (:caseIds)
        """
    )
    fun findShared(folderId: Long, otherFolderId: Long, caseIds: Collection<Long>): Flow<Long>

    /** 순서를 [caseIds] 대로 다시 매긴다. [caseIds] 는 그 folder 의 항목 전부여야 한다 — 부르는 쪽이 확인한다. */
    @Modifying
    @Query(
        """
        UPDATE test_case_folder_item
           SET position = array_position(string_to_array(:order, ',')::BIGINT[], test_case_id) - 1
         WHERE folder_id = :folderId AND test_case_id IN (:caseIds)
        """
    )
    suspend fun reorder(folderId: Long, caseIds: Collection<Long>, order: String = caseIds.joinToString(",")): Int

    /** 빠진 자리를 메워 0 부터 다시 매긴다. */
    @Modifying
    @Query(
        """
        UPDATE test_case_folder_item i
           SET position = r.rn
          FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY position, id) - 1 AS rn
                  FROM test_case_folder_item WHERE folder_id = :folderId) r
         WHERE i.id = r.id AND i.position <> r.rn
        """
    )
    suspend fun compact(folderId: Long): Int

    /** 숨김 쓰기는 실제로 바뀐 케이스 id 를 돌려준다. 이미 그 상태였던 것은 없다. */
    @Query(
        """
        UPDATE test_case_folder_item
           SET hidden_game_build_ids = array_append(hidden_game_build_ids, :gameBuildId)
         WHERE folder_id = :folderId AND test_case_id IN (:caseIds)
           AND NOT (:gameBuildId = ANY (hidden_game_build_ids))
        RETURNING test_case_id
        """
    )
    fun hide(folderId: Long, caseIds: Collection<Long>, gameBuildId: Long): Flow<Long>

    @Query(
        """
        UPDATE test_case_folder_item
           SET hidden_game_build_ids = array_remove(hidden_game_build_ids, :gameBuildId)
         WHERE folder_id = :folderId AND test_case_id IN (:caseIds)
           AND :gameBuildId = ANY (hidden_game_build_ids)
        RETURNING test_case_id
        """
    )
    fun unhide(folderId: Long, caseIds: Collection<Long>, gameBuildId: Long): Flow<Long>

    /** [fromFolderId] 의 항목을 순서와 숨김째 [toFolderId] 로 복사한다. 복제가 쓴다. */
    @Modifying
    @Query(
        """
        INSERT INTO test_case_folder_item (folder_id, test_case_id, position, hidden_game_build_ids)
        SELECT :toFolderId, test_case_id, position, hidden_game_build_ids
          FROM test_case_folder_item WHERE folder_id = :fromFolderId
        """
    )
    suspend fun copyAll(fromFolderId: Long, toFolderId: Long): Int

    /**
     * 케이스마다 처음 담긴 folder. 케이스 응답의 `folderId` 칸을 채운다 — 케이스가 folder 하나에만 들던 때의
     * 칸이라, 여러 folder 에 든 케이스는 먼저 담긴 쪽을 낸다.
     */
    @Query(
        """
        SELECT DISTINCT ON (i.test_case_id) i.test_case_id, i.folder_id
          FROM test_case_folder_item i
          JOIN test_case t ON t.id = i.test_case_id
         WHERE t.project_id = :projectId
         ORDER BY i.test_case_id, i.id
        """
    )
    fun findFirstFolders(projectId: Long): Flow<CaseFirstFolder>

    @Query("SELECT folder_id FROM test_case_folder_item WHERE test_case_id = :testCaseId ORDER BY id LIMIT 1")
    suspend fun findFirstFolderId(testCaseId: Long): Long?
}
