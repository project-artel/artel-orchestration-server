package kr.artel.orchestration.testrun.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testrun.dto.TestRunFolderCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunFolderListResponse
import kr.artel.orchestration.testrun.dto.TestRunFolderRenameRequest
import kr.artel.orchestration.testrun.dto.TestRunFolderResponse
import kr.artel.orchestration.testrun.entity.TestRunFolderEntity
import kr.artel.orchestration.testrun.repository.TestRunFolderRepository
import org.springframework.stereotype.Service

/**
 * 테스트 런 폴더(ARTEL-1049). 폴더는 한 빌드 버전 안에만 있다 — 부모 폴더와 같은 버전이어야 하고, 런은 자기 버전의
 * 폴더에만 든다([TestRunService.place]).
 *
 * 비멤버이거나 다른 프로젝트의 것이면 404 로 답한다. 같은 버전 · 같은 부모 아래 이름이 겹치면 409 다.
 */
@Service
class TestRunFolderService(
    private val folders: TestRunFolderRepository,
    private val gameBuilds: GameBuildRepository,
    private val projectAccessService: ProjectAccessService,
) {
    suspend fun list(projectId: Long, userId: Long): TestRunFolderListResponse {
        requireMember(projectId, userId)
        return TestRunFolderListResponse(folders.findByProjectIdOrderByNameAscIdAsc(projectId).toList().map { it.toResponse() })
    }

    suspend fun create(projectId: Long, userId: Long, request: TestRunFolderCreateRequest): TestRunFolderResponse {
        requireMember(projectId, userId)
        val name = nameOf(request.name)
        val parent = request.parentId?.let { folderOf(projectId, it) }
        val gameBuildId = parent?.gameBuildId ?: request.gameBuildId?.let { versionBuildId(projectId, it) }
        if (parent != null && request.gameBuildId != null && request.gameBuildId != parent.gameBuildId?.toString()) {
            throw BadRequestException("부모 폴더와 다른 빌드 버전에는 만들 수 없습니다.")
        }
        requireFreeName(projectId, gameBuildId, parent?.id, name, except = null)
        return folders.save(
            TestRunFolderEntity(projectId = projectId, gameBuildId = gameBuildId, parentId = parent?.id, name = name)
        ).toResponse()
    }

    suspend fun rename(projectId: Long, userId: Long, folderId: Long, request: TestRunFolderRenameRequest): TestRunFolderResponse {
        requireMember(projectId, userId)
        val folder = folderOf(projectId, folderId.toString())
        val name = nameOf(request.name)
        requireFreeName(projectId, folder.gameBuildId, folder.parentId, name, except = folder.id)
        return folders.save(folder.copy(name = name)).toResponse()
    }

    /** 폴더를 지운다. 하위 폴더는 함께 지워지고, 안의 런은 그 버전 바로 아래로 나온다(V111 의 FK). */
    suspend fun delete(projectId: Long, userId: Long, folderId: Long) {
        requireMember(projectId, userId)
        folders.delete(folderOf(projectId, folderId.toString()))
    }

    /** 이 프로젝트의 폴더. 없거나 남의 것이면 404 다. */
    suspend fun folderOf(projectId: Long, folderId: String): TestRunFolderEntity =
        folderId.toLongOrNull()?.let { folders.findById(it) }?.takeIf { it.projectId == projectId }
            ?: throw NotFoundException("폴더를 찾을 수 없습니다.")

    private suspend fun requireMember(projectId: Long, userId: Long) {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }

    private suspend fun versionBuildId(projectId: Long, gameBuildId: String): Long {
        val build = gameBuildId.toLongOrNull()?.let { gameBuilds.findById(it) }?.takeIf { it.projectId == projectId }
            ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        if (build.isEditor) {
            throw ConflictException("editor 는 게임 버전이 아니라서 폴더를 둘 수 없습니다.", code = "EDITOR_BUILD_IS_NOT_A_VERSION")
        }
        return requireNotNull(build.id)
    }

    private suspend fun requireFreeName(projectId: Long, gameBuildId: Long?, parentId: Long?, name: String, except: Long?) {
        val taken = folders.findByProjectIdOrderByNameAscIdAsc(projectId).toList().any {
            it.id != except && it.gameBuildId == gameBuildId && it.parentId == parentId && it.name == name
        }
        if (taken) throw ConflictException("같은 자리에 같은 이름의 폴더가 있습니다.", code = "FOLDER_NAME_TAKEN")
    }

    private fun nameOf(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) throw BadRequestException("폴더 이름을 적어 주세요.")
        if (name.length > 200) throw BadRequestException("폴더 이름은 200자를 넘을 수 없습니다.")
        return name
    }

    private fun TestRunFolderEntity.toResponse() = TestRunFolderResponse(
        id = requireNotNull(id).toString(),
        gameBuildId = gameBuildId?.toString(),
        parentId = parentId?.toString(),
        name = name,
    )
}
