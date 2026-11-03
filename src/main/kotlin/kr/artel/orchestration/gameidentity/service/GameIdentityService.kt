package kr.artel.orchestration.gameidentity.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.gameidentity.dto.GameIdentityListResponse
import kr.artel.orchestration.gameidentity.dto.GameIdentityResponse
import kr.artel.orchestration.gameidentity.dto.GameIdentityRow
import kr.artel.orchestration.gameidentity.dto.InternalGameIdentityListResponse
import kr.artel.orchestration.gameidentity.dto.InternalGameIdentityResponse
import kr.artel.orchestration.gameidentity.dto.PutGameIdentityRequest
import kr.artel.orchestration.gameidentity.repository.GameIdentityRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service

/**
 * 게임 값 선언의 조회·등록·삭제(V173).
 *
 * 공개 경로는 모두 프로젝트 멤버 검사(`ProjectAccessService.requireMember`)를 먼저 한다. 멤버가 아니면 프로젝트가
 * 없는 것처럼 404 다. MCP access JWT 로 들어온 요청도 같다(`DeveloperMacroService` 와 같은 규칙).
 *
 * developer macro 와 달리 agent-server 검사를 거치지 않는다. 선언은 실행할 원문이 아니라 사람이 쓴 문장이라 이 서버가
 * 볼 수 있는 것은 칸의 모양뿐이고, 그것은 DTO 검증이 본다.
 */
@Service
class GameIdentityService(
    private val repository: GameIdentityRepository,
    private val projectAccess: ProjectAccessService,
    private val projects: ProjectRepository,
    private val gameBuilds: GameBuildRepository,
) {

    suspend fun list(userId: Long, projectId: Long): GameIdentityListResponse {
        projectAccess.requireMember(projectId, userId)
        return GameIdentityListResponse(repository.findRows(projectId).toList().map { it.toResponse() })
    }

    /** 이름으로 넣거나 갱신하고 저장된 행을 돌려준다. 요청의 칸은 DTO 검증을 이미 거쳤다. */
    suspend fun put(userId: Long, projectId: Long, request: PutGameIdentityRequest): GameIdentityResponse {
        projectAccess.requireMember(projectId, userId)
        val gameBuildId = request.gameBuildId!!.toLong()
        requireBuildOfProject(userId, projectId, gameBuildId)
        return repository.upsertByName(
            projectId = projectId,
            name = request.name!!,
            kind = request.kind!!,
            base = request.base,
            meaning = request.meaning!!,
            changesWhen = request.changesWhen!!,
            identifies = request.identifies!!,
            role = request.role!!,
            gameBuildId = gameBuildId,
            createdBy = userId,
            updatedBy = userId,
        ).toResponse()
    }

    suspend fun delete(userId: Long, projectId: Long, name: String) {
        projectAccess.requireMember(projectId, userId)
        if (repository.deleteByProjectIdAndName(projectId, name) == 0) {
            throw NotFoundException("게임 값 선언을 찾을 수 없습니다.")
        }
    }

    /**
     * agent-server 엔진이 런 시작 때 읽는 프로젝트의 선언 전부. 멤버 검사가 없다 — 내부 포트 경로다.
     * 지운 프로젝트는 없는 것으로 본다(`DeveloperMacroService.listForAgent` 와 같다).
     */
    suspend fun listForAgent(projectId: Long): InternalGameIdentityListResponse {
        projects.findById(projectId)?.takeIf { it.deletedAt == null }
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        return InternalGameIdentityListResponse(repository.findRows(projectId).toList().map { it.toInternalResponse() })
    }

    /** 기준 build 가 이 프로젝트의 것인지. 아니면 developer macro 등록과 같은 404 다. */
    private suspend fun requireBuildOfProject(userId: Long, projectId: Long, gameBuildId: Long) {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
    }

    private fun GameIdentityRow.toResponse() = GameIdentityResponse(
        id = id.toString(),
        name = name,
        kind = kind,
        base = base,
        meaning = meaning,
        changesWhen = changesWhen,
        identifies = identifies,
        role = role,
        gameBuildId = gameBuildId.toString(),
        version = version,
        updatedAt = updatedAt,
    )

    private fun GameIdentityRow.toInternalResponse() = InternalGameIdentityResponse(
        name = name,
        kind = kind,
        base = base,
        meaning = meaning,
        changesWhen = changesWhen,
        identifies = identifies,
        role = role,
        gameBuildId = gameBuildId.toString(),
        version = version,
    )
}
