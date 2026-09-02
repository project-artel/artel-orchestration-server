package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service

/**
 * TestScenario 접근 인가(authorization) 전용 서비스(코루틴).
 *
 * "이 사용자가 접근해도 되는가"만 판단한다(비즈니스 로직과 분리). 접근 권한은 프로젝트 참여(project_member)
 * 여부로 결정되며, 참여자가 아니면 프로젝트/시나리오가 존재하지 않는 것처럼 null로 다룬다.
 *
 * 멤버십 판단은 공통 모듈 [ProjectAccessService]에 위임한다.
 *
 * **읽기와 쓰기를 두 함수로 가른다.** [readableScenario]는 `DEVELOPER` 등급을 통과시키고
 * [accessibleScenario]는 통과시키지 않는다. 한 함수에 등급을 넣으면 호출부 일곱 중 넷이 쓰기라
 * (`testScenarioUpdate`, `testScenarioApprove`, `updateExpectedLabels`, `delete`) 조회를 열려던
 * 한 줄이 남의 프로젝트 기대 판정 라벨까지 연다. 새 호출부를 붙일 때 그 자리가 무엇을 하는지 보고
 * 고른다.
 */
@Service
class TestScenarioAccessService(
    private val projectAccessService: ProjectAccessService,
    private val platformAccessService: PlatformAccessService,
    private val scenarioRepository: TestScenarioRepository
) {
    /** 사용자가 해당 프로젝트 참여자인지. */
    suspend fun isMember(projectId: Long, appUserId: Long): Boolean =
        projectAccessService.isMember(projectId, appUserId)

    /** 참여자이거나 `DEVELOPER` 등급인지. 읽는 자리만 쓴다. */
    suspend fun isReadable(projectId: Long, appUserId: Long): Boolean =
        isMember(projectId, appUserId) || platformAccessService.seesAllProjects(appUserId)

    /**
     * 시나리오를 찾고 그 프로젝트 참여자인지 확인. 없거나 비참여자면 null.
     *
     * **쓰기 경로가 쓰는 것이 이쪽이다.** 등급은 여기를 통과하지 못한다.
     */
    suspend fun accessibleScenario(testScenarioId: Long, appUserId: Long): TestScenarioEntity? {
        val scenario = scenarioRepository.findById(testScenarioId) ?: return null
        return if (isMember(scenario.projectId, appUserId)) scenario else null
    }

    /** [accessibleScenario]의 읽기 전용 형제. `DEVELOPER` 등급은 참여하지 않아도 통과한다. */
    suspend fun readableScenario(testScenarioId: Long, appUserId: Long): TestScenarioEntity? {
        val scenario = scenarioRepository.findById(testScenarioId) ?: return null
        return if (isReadable(scenario.projectId, appUserId)) scenario else null
    }
}
