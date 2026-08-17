package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.TestCaseListItem
import kr.artel.orchestration.testcase.dto.UncoveredScene
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

    /**
     * 이 프로젝트의 TestCase id 전량(2단계).
     *
     * 저작 결과를 검사하는 두 기준이 이 집합이다: 판정이 전량을 덮었는지, 스텝이 지목한 번호가
     * 실재하는지. 본문은 필요 없어서 id만 읽는다 — 1000건이라도 한 컬럼이다.
     */
    @Query("SELECT id FROM test_case WHERE project_id = :projectId")
    fun findIdsByProjectId(projectId: Long): Flow<Long>

    /**
     * 어떤 시나리오도 아직 건드리지 않은 케이스의 id(2단계).
     *
     * 커버 집합은 `test_scenario.steps`의 `case_id` 합집합이다 — **원장을 따로 저장하지 않는다.**
     * 값이 이미 있는데 복제하면 진실이 둘이 되고, 시나리오를 고칠 때마다 동기화가 숙제로 남는다.
     *
     * `case_id`가 없는 스텝(이동·준비 같은 브리지)은 자연히 빠진다 — 검증을 하지 않으므로 무엇도
     * 커버하지 않는다.
     *
     * 정렬을 `id ASC`로 고정하는 이유는 전량 목록과 같다: 이 값도 세션 프롬프트로 나가므로 순서가
     * 흔들리면 캐시 접두사가 깨진다.
     */
    @Query(
        """
        SELECT c.id FROM test_case c
        WHERE c.project_id = :projectId
          AND NOT EXISTS (
            SELECT 1 FROM test_scenario s, jsonb_array_elements(s.steps) e
            WHERE s.project_id = :projectId
              AND e->>'case_id' IS NOT NULL
              AND (e->>'case_id')::bigint = c.id
          )
        ORDER BY c.id ASC
        """
    )
    fun findUncoveredIdsByProjectId(projectId: Long): Flow<Long>

    /**
     * 미커버가 **어느 씬에 몇 건씩** 남았는지(ARTEL-403). 저작이 끝난 뒤 다음에 할 일을 권할 때 쓴다.
     *
     * id 목록만으로는 사람이 무엇이 남았는지 알 수 없다 — 번호는 화면에 내보내지도 않는 값이다.
     * 씬은 사용자가 아는 말이라 "전투 화면 12건이 남았다"가 곧 다음 요청이 된다.
     *
     * 많은 순으로 낸다. 다음에 할 일을 고르는 자리라 큰 덩어리가 먼저 보이는 편이 쓸모 있다.
     */
    @Query(
        """
        SELECT c.scene AS scene, count(*) AS count
        FROM test_case c
        WHERE c.project_id = :projectId
          AND NOT EXISTS (
            SELECT 1 FROM test_scenario s, jsonb_array_elements(s.steps) e
            WHERE s.project_id = :projectId
              AND e->>'case_id' IS NOT NULL
              AND (e->>'case_id')::bigint = c.id
          )
        GROUP BY c.scene
        ORDER BY count(*) DESC, c.scene ASC
        """
    )
    fun findScenesOfUncovered(projectId: Long): Flow<UncoveredScene>

    /** 프로젝트의 전체 케이스 수(ARTEL-403). 커버리지의 분모다. */
    suspend fun countByProjectId(projectId: Long): Long

    /**
     * 검증 상태별 건수(ARTEL-403). 화면의 두 축 중 "QA 런이 실제로 무엇을 냈는가" 쪽이다.
     *
     * 상태마다 한 번씩 부른다. 한 질의로 GROUP BY 하는 편이 짧지만 그러려면 (상태, 건수) 짝을
     * 담을 타입이 필요한데, 있는 타입을 재사용하면 필드 이름이 거짓말을 하게 된다(`scene`에
     * `VERIFIED`가 들어간다). 세 번 세는 값이 세 줄일 뿐이다.
     */
    suspend fun countByProjectIdAndVerificationStatus(projectId: Long, verificationStatus: String): Long

    /**
     * 명세 적재의 **보조** 키 — `spec_id`가 아직 없는 행만 고른다(ARTEL-329).
     *
     * spec_id가 붙기 전에 만들어진 행(손으로 만든 케이스, 이 계약 이전의 적재)을 새 명세가 이어받게
     * 하려고 둔다. **이미 다른 spec_id를 가진 행은 절대 고르지 않는다** — 씬+스텝은 케이스를 유일하게
     * 가리키지 못하기 때문이다. 실제 명세에서 `Map_scene / Map_scene에 진입해 관찰한다` 하나가
     * 사전조건만 다른 6건이었고, 이 조건이 없을 때 그 6건이 한 행으로 겹쳐 5건이 조용히 사라졌다.
     *
     * `findFirst`인 이유도 같다: 같은 씬+스텝이 여럿인 것이 정상이라, 단건 반환 시그니처는 언젠가
     * "결과가 유일하지 않다"로 적재 전체를 세운다. 정렬을 고정해 어느 행을 잇는지도 결정적으로 둔다.
     */
    suspend fun findFirstByProjectIdAndSceneAndStepAndSpecIdIsNullOrderByIdAsc(
        projectId: Long,
        scene: String,
        step: String
    ): TestCaseEntity?

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
