package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

/**
 * TestScenario 접근 인가(authorization) 전용 서비스.
 *
 * "이 사용자가 접근해도 되는가"만 판단한다(비즈니스 로직과 분리). 접근 권한은 프로젝트 참여(project_member)
 * 여부로 결정되며, 참여자가 아니면 프로젝트/시나리오가 존재하지 않는 것처럼 빈 결과로 다룬다.
 */
@Service
class TestScenarioAccessService(
    private val projectMemberRepository: ProjectMemberRepository,
    private val scenarioRepository: TestScenarioRepository
) {
    /** 사용자가 해당 프로젝트 참여자인지. */
    fun isMember(projectId: Long, appUserId: Long): Mono<Boolean> =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, appUserId).hasElement()

    /** 시나리오를 찾고 그 프로젝트 참여자인지 확인. 없거나 비참여자면 빈 Mono. */
    fun accessibleScenario(testScenarioId: Long, appUserId: Long): Mono<TestScenarioEntity> =
        scenarioRepository.findById(testScenarioId)
            .filterWhen { isMember(it.projectId, appUserId) }
}
