package kr.artel.orchestration.testscenario.service

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
 */
@Service
class TestScenarioAccessService(
    private val projectAccessService: ProjectAccessService,
    private val scenarioRepository: TestScenarioRepository
) {
    /** 사용자가 해당 프로젝트 참여자인지. */
    suspend fun isMember(projectId: Long, appUserId: Long): Boolean =
        projectAccessService.isMember(projectId, appUserId)

    /** 시나리오를 찾고 그 프로젝트 참여자인지 확인. 없거나 비참여자면 null. */
    suspend fun accessibleScenario(testScenarioId: Long, appUserId: Long): TestScenarioEntity? {
        val scenario = scenarioRepository.findById(testScenarioId) ?: return null
        return if (isMember(scenario.projectId, appUserId)) scenario else null
    }
}
