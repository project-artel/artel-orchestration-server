package kr.artel.orchestration.game.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.dto.GameInstanceListResponse
import kr.artel.orchestration.game.dto.GameInstanceResponse
import kr.artel.orchestration.game.dto.UpdateGameInstanceRequest
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.entity.GamePlatform
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.sdk.service.SessionManager
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 게임 인스턴스의 접근 규칙이 모이는 곳.
 *
 * 인스턴스를 여기서 만들지 않는다. SDK가 로그인하고 프로젝트를 고른 뒤 처음 등록할 때
 * 생긴다(`SdkRegistrationService`). 대시보드에서 미리 만들면 아직 어떤 설치본과도 연결되지
 * 않은, 영영 쓰이지 않는 행이 남는다.
 *
 * 프로젝트와 같은 규칙을 따른다. 참여자가 아니면 존재 여부조차 알리지 않고, 삭제는 soft
 * delete다. 역할로 갈리는 동작은 없다. 인스턴스를 정리하는 것은 개발 중 반복되는 작업이라,
 * OWNER만 할 수 있게 하면 팀원이 자기 설치본조차 지우지 못한다.
 *
 * 접근할 수 없는 대상은 null로 응답한다 — 컨트롤러가 404로 옮긴다.
 */
@Service
class GameInstanceService(
    private val instanceRepository: GameInstanceRepository,
    private val projectRepository: ProjectRepository,
    private val sessionManager: SessionManager,
    private val clock: Clock
) {
    suspend fun list(userId: Long, projectId: Long): GameInstanceListResponse? {
        requireAccessibleProject(projectId, userId) ?: return null
        val items = instanceRepository.findAllForMember(projectId, userId)
            .map { it.toResponse() }
            .toList()
        return GameInstanceListResponse(items)
    }

    suspend fun rename(
        userId: Long,
        projectId: Long,
        instanceId: Long,
        request: UpdateGameInstanceRequest
    ): GameInstanceResponse? {
        val instance = instanceRepository.findAccessibleById(instanceId, projectId, userId) ?: return null
        return instanceRepository.save(
            instance.copy(
                name = request.name?.trim()?.ifBlank { null } ?: instance.name,
                updatedAt = Instant.now(clock)
            )
        ).toResponse()
    }

    /**
     * 행을 지우지 않고 [GameInstanceEntity.deletedAt]만 채운다. 삭제된 인스턴스는 조회
     * 질의에서 함께 걸러지므로 그 시점부터 연결이 통하지 않는다. 같은 설치본이 다시 등록하면
     * 새 인스턴스로 나타난다.
     *
     * 성공했을 때 `true`, 접근할 수 없어 찾지 못했을 때 `false`를 돌려준다. `Unit`으로는
     * 컨트롤러가 "지웠음"과 "찾지 못했음"을 구분할 수 없다.
     */
    suspend fun delete(userId: Long, projectId: Long, instanceId: Long): Boolean {
        val instance = instanceRepository.findAccessibleById(instanceId, projectId, userId) ?: return false
        val now = Instant.now(clock)
        instanceRepository.save(instance.copy(deletedAt = now, updatedAt = now))
        return true
    }

    /**
     * 접근할 수 없거나 삭제된 프로젝트는 null이다. 컨트롤러가 404로 옮긴다.
     *
     * 목록은 인스턴스가 아니라 프로젝트를 대상으로 하므로, 인스턴스 조회로는 "프로젝트가
     * 없음"과 "인스턴스가 하나도 없음"을 구분할 수 없다.
     */
    private suspend fun requireAccessibleProject(projectId: Long, userId: Long): ProjectEntity? =
        projectRepository.findAccessibleById(projectId, userId)

    private fun GameInstanceEntity.toResponse(): GameInstanceResponse {
        val instanceId = requireNotNull(id)
        return GameInstanceResponse(
            id = instanceId.toString(),
            projectId = projectId.toString(),
            name = name,
            platform = platform.toPlatform(),
            connected = sessionManager.hasSession(instanceId.toString()),
            lastConnectedAt = lastConnectedAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /**
     * 저장된 값이 현재 enum에 없으면 [GamePlatform.UNITY]로 떨어뜨린다. 플랫폼 목록에서 값을
     * 빼는 변경이 기존 인스턴스를 통째로 조회 불가로 만드는 일을 막는다.
     */
    private fun String.toPlatform(): GamePlatform =
        GamePlatform.entries.firstOrNull { it.name == this } ?: GamePlatform.UNITY
}
