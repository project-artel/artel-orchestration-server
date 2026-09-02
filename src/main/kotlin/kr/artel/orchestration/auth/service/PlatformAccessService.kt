package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import org.springframework.stereotype.Service

/**
 * 프로젝트 밖의 등급으로 판단하는 인가. [kr.artel.orchestration.project.service.ProjectAccessService]가
 * "이 프로젝트의 참여자인가"를 답하는 것과 짝을 이뤄, 이쪽은 "참여를 따지지 않아도 되는 사람인가"를
 * 답한다.
 *
 * **여는 것은 조회뿐이다.** 이 서비스를 쓰는 자리를 늘릴 때 그 자리가 무엇을 하는지 먼저 본다.
 * `ProjectAccessService.requireMember`나 `TestScenarioAccessService.accessibleScenario`처럼
 * 읽기와 쓰기가 함께 지나는 함수에서 이것을 부르면, 조회를 열려던 한 줄이 프로젝트 삭제와 기획서
 * 업로드까지 연다. 그래서 그 두 함수는 이 서비스를 모르고, 읽는 호출부만 따로 부른다.
 *
 * 등급을 토큰이 아니라 매번 DB에서 읽는 이유도 같은 종류다. JWT에 실으면 등급을 내려도 access
 * 토큰이 만료될 때까지(15분) 그 사람이 계속 전체를 본다. 읽는 비용은 넓히는 경로에만 든다 —
 * [kr.artel.orchestration.auth.web.CurrentUserId] 리졸버에 넣으면 그 애너테이션을 쓰는 모든
 * 엔드포인트가 질의 하나씩을 더 낸다.
 */
@Service
class PlatformAccessService(
    private val appUserRepository: AppUserRepository
) {
    /**
     * 참여하지 않은 프로젝트까지 조회할 수 있는 사람인지.
     *
     * 사용자 행이 없으면 false다. 그 상태는 세션이 가리키는 사용자가 지워진 경우인데, 여기서
     * 예외를 던지면 삭제된 사용자의 토큰이 401이 아니라 500을 받는다.
     */
    suspend fun seesAllProjects(userId: Long): Boolean =
        appUserRepository.findById(userId)?.platformRole == PlatformRole.DEVELOPER.name
}
