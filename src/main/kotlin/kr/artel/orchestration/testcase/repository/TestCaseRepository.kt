package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.TestCaseCatalogEntry
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * TestCase 조회 리포지토리(코루틴). 조회는 프로젝트 스코프이며, 대분류(category)·검증상태로 선택 필터한다.
 * 멱등 적재(Agent 재전송 중복 방지)를 위해 (project_id, category, title) 조회도 제공한다.
 */
interface TestCaseRepository : CoroutineCrudRepository<TestCaseEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<TestCaseEntity>

    /**
     * 저작 Agent에 실을 전량 목록(ARTEL-318). 엔티티가 아니라 [TestCaseCatalogEntry]로 좁혀 읽는다.
     *
     * **네 컬럼만 고르는 것이 이 질의의 요점이다.** 전량을 읽으므로 `precondition`/`expected`(TEXT)까지
     * 끌어오면 프로젝트당 수백 KB가 오가는데, 목록은 그 본문을 쓰지 않는다.
     *
     * **`ORDER BY id ASC`는 취향이 아니라 계약이다.** 이 목록은 Agent 프롬프트의 앞쪽 고정 블록에
     * 실려 프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨져 전량을 매 턴 다시
     * 청구한다. 같은 파일의 다른 조회들이 최신순(id DESC)인 것과 다른 이유가 이것이다.
     */
    @Query(
        """
        SELECT id, category, title, verification_status
        FROM test_case
        WHERE project_id = :projectId
        ORDER BY id ASC
        """
    )
    fun findCatalogByProjectIdOrderByIdAsc(projectId: Long): Flow<TestCaseCatalogEntry>

    fun findByProjectIdAndCategoryOrderByIdDesc(projectId: Long, category: String): Flow<TestCaseEntity>

    fun findByProjectIdAndVerificationStatusOrderByIdDesc(
        projectId: Long,
        verificationStatus: String
    ): Flow<TestCaseEntity>

    suspend fun findByProjectIdAndCategoryAndTitle(projectId: Long, category: String, title: String): TestCaseEntity?
}
