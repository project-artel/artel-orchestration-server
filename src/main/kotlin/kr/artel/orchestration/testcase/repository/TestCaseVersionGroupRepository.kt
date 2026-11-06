package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.entity.TestCaseVersionGroupEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** 버전 묶음과 버전별 설정(묶음 · 숨김)(ARTEL-1023). */
interface TestCaseVersionGroupRepository : CoroutineCrudRepository<TestCaseVersionGroupEntity, Long> {

    fun findByProjectIdOrderByNameAscIdAsc(projectId: Long): Flow<TestCaseVersionGroupEntity>

    suspend fun findByIdAndProjectId(id: Long, projectId: Long): TestCaseVersionGroupEntity?

    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM test_case_version_group
             WHERE project_id = :projectId AND name = :name AND id IS DISTINCT FROM :excludeId
        )
        """
    )
    suspend fun existsName(projectId: Long, name: String, excludeId: Long?): Boolean

    /** 프로젝트 build 의 설정. 설정 행이 없는 build 는 결과에 없다(묶음 없음 · 보임). */
    @Query(
        """
        SELECT s.game_build_id, s.group_id, s.hidden
          FROM test_case_version_setting s
          JOIN game_build gb ON gb.id = s.game_build_id
         WHERE gb.project_id = :projectId
        """
    )
    fun findSettings(projectId: Long): Flow<VersionSetting>

    @Modifying
    @Query(
        """
        INSERT INTO test_case_version_setting (game_build_id, group_id, hidden, updated_at)
        VALUES (:gameBuildId, :groupId, FALSE, now())
        ON CONFLICT (game_build_id) DO UPDATE SET group_id = EXCLUDED.group_id, updated_at = now()
        """
    )
    suspend fun upsertGroup(gameBuildId: Long, groupId: Long?): Int

    @Modifying
    @Query(
        """
        INSERT INTO test_case_version_setting (game_build_id, group_id, hidden, updated_at)
        VALUES (:gameBuildId, NULL, :hidden, now())
        ON CONFLICT (game_build_id) DO UPDATE SET hidden = EXCLUDED.hidden, updated_at = now()
        """
    )
    suspend fun upsertHidden(gameBuildId: Long, hidden: Boolean): Int
}

/** [TestCaseVersionGroupRepository.findSettings] 의 한 줄. */
data class VersionSetting(
    val gameBuildId: Long,
    val groupId: Long?,
    val hidden: Boolean,
)
