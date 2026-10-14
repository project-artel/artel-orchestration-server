package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.dto.TestCaseFolderResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionCopyRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupAssignRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionHiddenRequest
import kr.artel.orchestration.testcase.dto.parseOptionalWireId
import kr.artel.orchestration.testcase.entity.TestCaseVersionGroupEntity
import kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionGroupRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 라이브러리 버전 목록의 정리(ARTEL-1023): 버전 묶음, 버전 숨김, 버전의 케이스를 folder 로 복제.
 *
 * 묶음은 버전과 같은 층에 하나뿐이다(예: `1.X`, `2.X`). 숨긴 버전은 지우지 않고 목록의 숨긴 버전 보기에서만 보인다.
 * 쓰기는 folder 와 같은 프로젝트 lock 을 잡는다 — 이름이 비었는지 본 뒤 쓰므로 동시에 오면 unique 에 걸린다.
 *
 * 비멤버, 다른 프로젝트 build · 묶음은 404 다.
 */
@Service
class TestCaseVersionLayoutService(
    private val groups: TestCaseVersionGroupRepository,
    private val gameBuilds: GameBuildRepository,
    private val versions: TestCaseVersionRepository,
    private val folderService: TestCaseFolderService,
    private val folderItems: TestCaseFolderItemRepository,
    private val transactionalOperator: TransactionalOperator,
) {

    suspend fun createGroup(projectId: Long, userId: Long, request: TestCaseVersionGroupRequest): TestCaseVersionGroupResponse {
        folderService.requireMember(projectId, userId)
        val name = nameOf(request.name)
        val saved = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            requireFreeName(projectId, name, null)
            groups.save(TestCaseVersionGroupEntity(projectId = projectId, name = name))
        }
        return responseOf(saved)
    }

    suspend fun renameGroup(
        projectId: Long,
        userId: Long,
        groupId: Long,
        request: TestCaseVersionGroupRequest,
    ): TestCaseVersionGroupResponse {
        folderService.requireMember(projectId, userId)
        val name = nameOf(request.name)
        val saved = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val group = requireGroup(projectId, groupId)
            requireFreeName(projectId, name, groupId)
            groups.save(group.copy(name = name))
        }
        return responseOf(saved)
    }

    /** 묶음을 지운다. 안의 버전은 묶음 없음으로 돌아간다(V106 FK). */
    suspend fun deleteGroup(projectId: Long, userId: Long, groupId: Long) {
        folderService.requireMember(projectId, userId)
        transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            groups.delete(requireGroup(projectId, groupId))
        }
    }

    /** 버전을 묶음에 넣는다. `groupId` 가 없으면 묶음에서 뺀다. */
    suspend fun assignGroup(projectId: Long, userId: Long, gameBuildId: Long, request: TestCaseVersionGroupAssignRequest) {
        requireBuild(projectId, userId, gameBuildId)
        val groupId = parseOptionalWireId(request.groupId, "groupId")
        transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            groupId?.let { requireGroup(projectId, it) }
            groups.upsertGroup(gameBuildId, groupId)
        }
    }

    suspend fun setHidden(projectId: Long, userId: Long, gameBuildId: Long, request: TestCaseVersionHiddenRequest) {
        requireBuild(projectId, userId, gameBuildId)
        val hidden = request.hidden ?: throw BadRequestException("hidden 이 필요합니다.")
        groups.upsertHidden(gameBuildId, hidden)
    }

    /**
     * 버전의 케이스를 담은 folder 를 새로 만든다. 그 버전에 몫이 있는 케이스를 케이스 id 순으로 담는다. 몫의 숨김은
     * 없다 — 새 folder 다.
     */
    suspend fun copyToFolder(
        projectId: Long,
        userId: Long,
        gameBuildId: Long,
        request: TestCaseVersionCopyRequest,
    ): TestCaseFolderResponse {
        val build = requireBuild(projectId, userId, gameBuildId)
        val parentId = parseOptionalWireId(request.parentId, "parentId")
        val baseName = request.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${build.label?.takeIf { it.isNotBlank() } ?: build.version} 사본"
        val folderId = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val folder = folderService.createWithFreeName(projectId, parentId, baseName)
            val caseIds = versions.findByGameBuildId(gameBuildId).toList().map { it.testCaseId }.sorted()
            caseIds.chunked(CHUNK).forEach { chunk -> folderItems.append(projectId, requireNotNull(folder.id), chunk).toList() }
            requireNotNull(folder.id)
        }
        return folderService.responseOf(folderId)
    }

    private suspend fun requireBuild(projectId: Long, userId: Long, gameBuildId: Long) =
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("버전을 찾을 수 없습니다.")

    private suspend fun requireGroup(projectId: Long, groupId: Long): TestCaseVersionGroupEntity =
        groups.findByIdAndProjectId(groupId, projectId) ?: throw NotFoundException("버전 묶음을 찾을 수 없습니다.")

    private suspend fun requireFreeName(projectId: Long, name: String, excludeId: Long?) {
        if (groups.existsName(projectId, name, excludeId)) throw ConflictException("같은 이름의 버전 묶음이 있습니다.")
    }

    private fun nameOf(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > TestCaseFolderService.NAME_MAX_LENGTH) {
            throw BadRequestException("묶음 이름은 1자 이상 ${TestCaseFolderService.NAME_MAX_LENGTH}자 이하여야 합니다.")
        }
        return name
    }

    private fun responseOf(group: TestCaseVersionGroupEntity) =
        TestCaseVersionGroupResponse(requireNotNull(group.id).toString(), group.name)

    private companion object {
        /** `IN (...)` bind 변수 수의 상한(`WIRE_ID_LIMIT`)과 같다. */
        const val CHUNK = 500
    }
}
