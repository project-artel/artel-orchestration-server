package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.CaseVerdictHistoryRow
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** 케이스의 버전별 몫(ARTEL-1018). */
interface TestCaseVersionRepository : CoroutineCrudRepository<TestCaseVersionEntity, Long> {

    fun findByGameBuildId(gameBuildId: Long): Flow<TestCaseVersionEntity>

    suspend fun findByTestCaseIdAndGameBuildId(testCaseId: Long, gameBuildId: Long): TestCaseVersionEntity?

    suspend fun countByTestCaseId(testCaseId: Long): Long

    /**
     * 각 케이스의 **[gameBuildId] 보다 앞선 버전 중 가장 최근 것**의 상태. 새 버전 몫이 REGRESSION 으로
     * 시작할지 정할 때 읽는다. 앞선 버전이 없는 케이스는 결과에 없다.
     *
     * editor 행은 게임 버전이 아니라서 앞선 버전으로 고르지 않고, [gameBuildId] 가 editor 행이면 결과가 비어 모두
     * DRAFT 로 시작한다(ARTEL-1014).
     */
    @Query(
        """
        SELECT DISTINCT ON (v.test_case_id) v.test_case_id, v.verification_status
          FROM test_case_version v
          JOIN game_build pb ON pb.id = v.game_build_id
          JOIN game_build cur ON cur.id = :gameBuildId
         WHERE v.test_case_id IN (:testCaseIds)
           AND pb.is_editor = FALSE
           AND cur.is_editor = FALSE
           AND (pb.created_at, pb.id) < (cur.created_at, cur.id)
         ORDER BY v.test_case_id, pb.created_at DESC, pb.id DESC
        """
    )
    fun findPreviousStatuses(testCaseIds: Collection<Long>, gameBuildId: Long): Flow<PreviousStatus>

    /**
     * [testCaseIds] 의 정체(`test_case`)에 가장 최근 버전을 비춘다(ARTEL-1018).
     *
     * 상태는 늘 비춘다. 내용은 지도가 낸 케이스(`metadata.origin = content-map`)일 때만 비춘다 — 사람이 쓴
     * 케이스는 사람이 고친 내용이 원본이고, 버전 몫은 판정을 받던 때의 스냅샷일 뿐이다.
     * 버전이 하나도 없는 케이스는 건드리지 않는다.
     *
     * "가장 최근" 은 게임 버전(editor 가 아닌 행) 중에서 고른다. editor 행은 게임 버전이 아니라서(ARTEL-1014)
     * 그 케이스가 editor 행에만 있을 때만 비춘다.
     */
    @Modifying
    @Query(
        """
        UPDATE test_case t
           SET verification_status = v.verification_status,
               scene          = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.scene ELSE t.scene END,
               step           = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.step ELSE t.step END,
               precondition   = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.precondition ELSE t.precondition END,
               condition      = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.condition ELSE t.condition END,
               expected_value = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.expected_value ELSE t.expected_value END,
               expected_items = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.expected_items ELSE t.expected_items END,
               status         = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.status ELSE t.status END,
               capability_key = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.capability_key ELSE t.capability_key END,
               metadata       = CASE WHEN t.metadata ->> 'origin' = 'content-map' THEN v.metadata ELSE t.metadata END,
               updated_at     = now()
          FROM (SELECT DISTINCT ON (tv.test_case_id) tv.*
                  FROM test_case_version tv
                  JOIN game_build gb ON gb.id = tv.game_build_id
                 WHERE tv.test_case_id IN (:testCaseIds)
                 ORDER BY tv.test_case_id, gb.is_editor, gb.created_at DESC, gb.id DESC) v
         WHERE t.id = v.test_case_id
        """
    )
    suspend fun syncIdentities(testCaseIds: Collection<Long>): Int

    /**
     * [testCaseId] 의 버전별 몫을 오래된 build 부터(ARTEL-1018). 버전 몫이 없는 케이스는 빈 결과다. editor 행은
     * 게임 버전이 아니라서 빠진다(ARTEL-1014).
     */
    @Query(
        """
        SELECT v.test_case_id, gb.id AS game_build_id, gb.version, gb.label,
               v.verification_status, gb.created_at AS build_created_at
          FROM test_case_version v
          JOIN test_case t ON t.id = v.test_case_id
          JOIN game_build gb ON gb.id = v.game_build_id
         WHERE v.test_case_id = :testCaseId AND t.project_id = :projectId
           AND gb.is_editor = FALSE
         ORDER BY gb.created_at ASC, gb.id ASC
        """
    )
    fun findHistory(projectId: Long, testCaseId: Long): Flow<CaseVerdictHistoryRow>

    /**
     * [testCaseIds] 가운데 **지도가 낸 케이스인데 [gameBuildId] 에 버전 몫이 없는 것**. TS 를 그 버전의 TR
     * 로 가져올 때 "이 버전에 없는 TC" 로 알린다. 사람이 쓴 케이스는 버전에 묶이지 않으므로 세지 않는다.
     */
    @Query(
        """
        SELECT t.id FROM test_case t
         WHERE t.project_id = :projectId
           AND t.id IN (:testCaseIds)
           AND t.metadata ->> 'origin' = 'content-map'
           AND NOT EXISTS (
               SELECT 1 FROM test_case_version v
                WHERE v.test_case_id = t.id AND v.game_build_id = :gameBuildId
           )
        """
    )
    fun findMapCaseIdsMissingIn(projectId: Long, testCaseIds: Collection<Long>, gameBuildId: Long): Flow<Long>
}

/** [TestCaseVersionRepository.findPreviousStatuses] 의 한 줄. */
data class PreviousStatus(
    val testCaseId: Long,
    val verificationStatus: String,
)
