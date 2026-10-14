package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.CaseVerdictHistoryRow
import kr.artel.orchestration.testcase.dto.VersionStatusCount
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** 케이스의 버전별 몫(ARTEL-1018). */
interface TestCaseVersionRepository : CoroutineCrudRepository<TestCaseVersionEntity, Long> {

    fun findByGameBuildId(gameBuildId: Long): Flow<TestCaseVersionEntity>

    /**
     * 이 build 에서 지도로 TC 를 만든 적이 있는지(ARTEL-1040). 지도 몫만 `content_map_id` 를 든다 — 사용자 생성 몫은
     * null 이라, 버전에 넣어 둔 사용자 생성 TC 가 지도 생성을 막지 않는다.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM test_case_version WHERE game_build_id = :gameBuildId AND content_map_id IS NOT NULL)"
    )
    suspend fun existsMapCasesByGameBuildId(gameBuildId: Long): Boolean

    /**
     * 이 build 의 지도 몫 수와 그중 판정이 난 수(ARTEL-1041). 덮어쓰기 경고가 "무엇을 잃을 수 있나" 를 말할 때 쓴다.
     * 집계만 있는 질의라 지도 몫이 없어도 행 하나(전부 0)가 돌아온다.
     */
    @Query(
        """
        SELECT count(*) FILTER (WHERE content_map_id IS NOT NULL) AS map_cases,
               count(*) FILTER (WHERE content_map_id IS NOT NULL
                                  AND verification_status IN ('VERIFIED', 'FAILED', 'REGRESSION')) AS judged
          FROM test_case_version
         WHERE game_build_id = :gameBuildId
        """
    )
    suspend fun countMapCasesByGameBuildId(gameBuildId: Long): MapCaseCount?

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

    /** 버전마다 상태별 몫 수(ARTEL-1021). `manual` 은 사람이 쓴 케이스(지도 출신이 아님)의 몫이다. */
    @Query(
        """
        SELECT v.game_build_id,
               (t.metadata ->> 'origin' IS DISTINCT FROM 'content-map') AS manual,
               v.verification_status,
               COUNT(*) AS total
          FROM test_case_version v
          JOIN test_case t ON t.id = v.test_case_id
         WHERE t.project_id = :projectId
         GROUP BY 1, 2, 3
        """
    )
    fun countByBuild(projectId: Long): Flow<VersionStatusCount>

    /**
     * 같은 프로젝트에서 [gameBuildId] 바로 앞의 게임 버전. 없으면 null(ARTEL-1031). editor 행은 게임 버전이 아니라서
     * 고르지 않고, [gameBuildId] 가 editor 행이면 null 이다(ARTEL-1014).
     */
    @Query(
        """
        SELECT prev.id FROM game_build prev
          JOIN game_build cur ON cur.id = :gameBuildId AND prev.project_id = cur.project_id
         WHERE prev.is_editor = FALSE
           AND cur.is_editor = FALSE
           AND (prev.created_at, prev.id) < (cur.created_at, cur.id)
         ORDER BY prev.created_at DESC, prev.id DESC
         LIMIT 1
        """
    )
    suspend fun findPreviousBuildId(gameBuildId: Long): Long?

    /** [fromBuildId] 에 몫이 있고 [toBuildId] 에는 없는 사용자 생성 케이스. 다음 버전으로 이어 넣을 것들이다. */
    @Query(
        """
        SELECT v.test_case_id
          FROM test_case_version v
          JOIN test_case t ON t.id = v.test_case_id
         WHERE v.game_build_id = :fromBuildId
           AND t.metadata ->> 'origin' IS DISTINCT FROM 'content-map'
           AND NOT EXISTS (SELECT 1 FROM test_case_version w WHERE w.test_case_id = v.test_case_id AND w.game_build_id = :toBuildId)
         ORDER BY v.test_case_id
        """
    )
    fun findManualCaseIdsToCarry(fromBuildId: Long, toBuildId: Long): Flow<Long>

    /** 어느 버전이든 몫이 하나라도 있는 케이스. 나머지가 "버전 없음" 이다(ARTEL-1023). */
    @Query(
        """
        SELECT DISTINCT v.test_case_id
          FROM test_case_version v
          JOIN test_case t ON t.id = v.test_case_id
         WHERE t.project_id = :projectId
        """
    )
    fun findVersionedCaseIds(projectId: Long): Flow<Long>

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

/** [TestCaseVersionRepository.countMapCasesByGameBuildId] 결과. */
data class MapCaseCount(val mapCases: Long, val judged: Long)
