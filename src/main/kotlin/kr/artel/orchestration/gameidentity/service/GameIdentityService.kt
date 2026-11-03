package kr.artel.orchestration.gameidentity.service

import jakarta.validation.Validator
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.gameidentity.dto.*
import kr.artel.orchestration.gameidentity.repository.GameIdentityRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GameIdentityService(
    private val repository: GameIdentityRepository,
    private val projectAccess: ProjectAccessService,
    private val projects: ProjectRepository,
    private val gameBuilds: GameBuildRepository,
    private val instances: GameInstanceRepository,
    private val validator: Validator,
) {
    suspend fun list(userId: Long, projectId: Long, gameBuildId: Long? = null, includeDeleted: Boolean = false): GameIdentityListResponse {
        projectAccess.requireMember(projectId, userId)
        if (gameBuildId != null) requireBuildOfProject(userId, projectId, gameBuildId)
        val rows = if (gameBuildId == null) repository.findRows(projectId, includeDeleted)
            else repository.findBuildRows(projectId, gameBuildId, includeDeleted)
        return GameIdentityListResponse(rows.toList().map { it.toResponse() })
    }

    suspend fun history(userId: Long, projectId: Long, name: String): GameIdentityListResponse {
        projectAccess.requireMember(projectId, userId)
        return GameIdentityListResponse(repository.history(projectId, name).toList().map { it.toResponse() })
    }

    @Transactional
    suspend fun put(userId: Long, projectId: Long, request: PutGameIdentityRequest, source: String = "WEB"): GameIdentityResponse {
        projectAccess.requireMember(projectId, userId)
        val buildId = request.gameBuildId!!.toLong()
        requireBuildOfProject(userId, projectId, buildId)
        lock(projectId)
        val selected = selected(projectId, buildId, request.name!!)
        requireVersion(selected, request.expectedVersion!!)
        if (selected != null && !selected.deleted && selected.source == source && selected.sameDeclaration(request)) {
            return selected.toResponse()
        }
        return append(projectId, buildId, request, source, false, userId).toResponse()
    }

    @Transactional
    suspend fun delete(userId: Long, projectId: Long, name: String, gameBuildId: Long, expectedVersion: Int, source: String = "WEB") {
        projectAccess.requireMember(projectId, userId)
        requireBuildOfProject(userId, projectId, gameBuildId)
        lock(projectId)
        val selected = selected(projectId, gameBuildId, name)
        requireVersion(selected, expectedVersion)
        if (selected == null || selected.deleted) throw NotFoundException("게임 값 선언을 찾을 수 없습니다.")
        append(projectId, gameBuildId, selected.toRequest(), source, true, userId)
    }

    suspend fun listForAgent(projectId: Long, gameBuildId: Long? = null): InternalGameIdentityListResponse {
        projects.findById(projectId)?.takeIf { it.deletedAt == null }
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        if (gameBuildId != null && gameBuilds.findById(gameBuildId)?.projectId != projectId) {
            throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
        }
        return InternalGameIdentityListResponse(snapshot(projectId, gameBuildId))
    }

    // build가 없는 구형 instance에 프로젝트 최신 선언을 상속하지 않는다.
    suspend fun snapshot(projectId: Long, gameBuildId: Long?): List<InternalGameIdentityResponse> =
        if (gameBuildId == null) emptyList()
        else repository.findBuildRows(projectId, gameBuildId, false).toList().map { it.toInternalResponse() }

    @Transactional
    suspend fun recordSdkCatalog(instanceId: Long, catalog: SdkGameIdentityCatalog) {
        val instance = instances.findById(instanceId)?.takeIf { it.deletedAt == null } ?: throw NotFoundException()
        val buildId = instance.lastGameBuildId ?: throw BadRequestException("게임 빌드가 등록되지 않았습니다.")
        if (gameBuilds.findById(buildId)?.projectId != instance.projectId) throw NotFoundException()
        val requests = catalog.identities.map { it.forBuild(buildId) }
        // 전체 검증이 끝나기 전에는 빠진 항목의 tombstone도 만들지 않는다.
        if (requests.any { validator.validate(it).isNotEmpty() } || requests.map { it.name }.distinct().size != requests.size) {
            throw BadRequestException("게임 값 선언 목록이 올바르지 않습니다.")
        }
        lock(instance.projectId)
        val selected = repository.findBuildRows(instance.projectId, buildId, true).toList().associateBy { it.name }
        for (request in requests) {
            val current = selected[request.name]
            if (current?.source == "WEB" || current?.source == "MCP") continue
            if (current != null && !current.deleted && current.sameDeclaration(request)) continue
            append(instance.projectId, buildId, request, "SDK", false, null)
        }
        val names = requests.map { it.name }.toSet()
        for (current in selected.values) {
            if (current.source == "SDK" && !current.deleted && current.name !in names) {
                append(instance.projectId, buildId, current.toRequest(), "SDK", true, null)
            }
        }
    }

    private suspend fun lock(projectId: Long) {
        repository.lockProject(projectId) ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }

    private suspend fun selected(projectId: Long, buildId: Long, name: String) =
        repository.findBuildRows(projectId, buildId, true).toList().find { it.name == name }

    private fun requireVersion(selected: GameIdentityRow?, expectedVersion: Int) {
        if (expectedVersion < 0 || (selected?.version ?: 0) != expectedVersion) {
            throw ConflictException("게임 값 선언이 변경되었습니다. 목록을 다시 조회해 주세요.")
        }
    }

    private suspend fun append(projectId: Long, buildId: Long, request: PutGameIdentityRequest,
        source: String, deleted: Boolean, createdBy: Long?): GameIdentityRow {
        val revision = repository.insertRevision(projectId, request.name!!, request.kind!!, request.base,
            request.meaning!!, request.changesWhen!!, request.identifies!!, request.role!!, buildId,
            source, deleted, createdBy)
        repository.bind(projectId, buildId, revision.name, revision.id)
        return revision
    }

    private suspend fun requireBuildOfProject(userId: Long, projectId: Long, gameBuildId: Long) {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
    }

    private fun GameIdentityRow.sameDeclaration(request: PutGameIdentityRequest) =
        kind == request.kind && base == request.base && meaning == request.meaning &&
            changesWhen == request.changesWhen && identifies == request.identifies && role == request.role

    private fun GameIdentityRow.toRequest() = PutGameIdentityRequest(name = name, gameBuildId = gameBuildId.toString(),
        expectedVersion = version, kind = kind, base = base, meaning = meaning, changesWhen = changesWhen,
        identifies = identifies, role = role)

    private fun GameIdentityRow.toResponse() = GameIdentityResponse(
        id = id.toString(), name = name, kind = kind, base = base, meaning = meaning, changesWhen = changesWhen,
        identifies = identifies, role = role, gameBuildId = gameBuildId.toString(), version = version,
        updatedAt = updatedAt, revisionId = id.toString(), source = source, deleted = deleted,
        createdBy = createdBy?.toString(), createdAt = createdAt)

    private fun GameIdentityRow.toInternalResponse() = InternalGameIdentityResponse(
        name = name, kind = kind, base = base, meaning = meaning, changesWhen = changesWhen,
        identifies = identifies, role = role, gameBuildId = gameBuildId.toString(), version = version,
        revisionId = id.toString(), source = source, deleted = deleted, createdBy = createdBy?.toString(), createdAt = createdAt)
}
