package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.TestCaseListItem
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * TestCase 조회 리포지토리(코루틴). 조회는 프로젝트 스코프이며, 씬(scene)·검증상태로 선택 필터한다.
 * 멱등 적재(Agent 재전송 중복 방지)를 위해 spec_id 조회와 (project_id, scene, step) 조회를 함께 제공한다.
 */
interface TestCaseRepository : CoroutineCrudRepository<TestCaseEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<TestCaseEntity>

    /**
     * 저작 Agent에 실을 전량 목록(ARTEL-318). 엔티티가 아니라 [TestCaseListItem]로 좁혀 읽는다.
     *
     * 엔티티를 그대로 읽지 않는 것은 Agent가 쓰지 않는 컬럼(`last_verified_build_id`, 타임스탬프)까지
     * 프롬프트로 흘러가지 않게 하기 위해서다. 본문(`precondition`/`expected`)은 **의도적으로 포함한다** —
     * 고른 케이스로 스텝을 쓰려면 필요하고, 빼면 다시 가져오는 왕복이 생긴다([TestCaseListItem] 참고).
     *
     * **`ORDER BY id ASC`는 취향이 아니라 계약이다.** 이 목록은 Agent 프롬프트의 앞쪽 고정 블록에
     * 실려 프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨져 전량을 매 턴 다시
     * 청구한다. 같은 파일의 다른 조회들이 최신순(id DESC)인 것과 다른 이유가 이것이다.
     */
    @Query(
        """
        SELECT id, scene, step, precondition, expected_value, verification_status
        FROM test_case
        WHERE project_id = :projectId
        ORDER BY id ASC
        """
    )
    fun findTestCaseListByProjectIdOrderByIdAsc(projectId: Long): Flow<TestCaseListItem>

    fun findByProjectIdAndSceneOrderByIdDesc(projectId: Long, scene: String): Flow<TestCaseEntity>

    fun findByProjectIdAndVerificationStatusOrderByIdDesc(
        projectId: Long,
        verificationStatus: String
    ): Flow<TestCaseEntity>

    suspend fun findByProjectIdAndSceneAndStep(projectId: Long, scene: String, step: String): TestCaseEntity?

    /** 명세 적재의 멱등 키(ARTEL-329). spec_id가 있는 케이스는 문구가 바뀌어도 같은 행으로 이어진다. */
    suspend fun findByProjectIdAndSpecId(projectId: Long, specId: String): TestCaseEntity?

    /**
     * 이 프로젝트가 이미 그 판의 명세를 받아 뒀는가(ARTEL-329).
     *
     * SDK 재등록마다 같은 명세가 다시 오는데, 그때마다 수백 행을 upsert하고 XLSX를 새로 써서
     * S3에 올릴 이유가 없다. 한 건이라도 그 revision이면 같은 판이 이미 반영된 것이다.
     */
    suspend fun existsByProjectIdAndSpecRevision(projectId: Long, specRevision: Int): Boolean
}
