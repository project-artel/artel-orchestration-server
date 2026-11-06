package kr.artel.orchestration.issue.folder

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

private const val NAME_MAX_LENGTH = 200
private const val ITEMS_MAX = 500

/**
 * 이슈 folder 의 목록 · 생성 · 수정 · 삭제 · 복제와 항목 담기 · 빼기(ARTEL-1114).
 *
 * 규칙은 케이스 folder(`TestCaseFolderService`)와 같다. 이름 중복 · 순환 확인은 읽은 뒤에 쓰므로, 한 프로젝트의
 * folder 쓰기는 advisory lock 으로 한 번에 하나씩 실행한다. 비멤버와 다른 프로젝트 folder · 이슈는 404 다.
 */
@Service
class IssueFolderService(
    private val folders: IssueFolderRepository,
    private val projectAccess: ProjectAccessService,
    private val transactions: TransactionalOperator,
) {
    suspend fun list(projectId: Long, userId: Long): IssueFolderListResponse {
        requireMember(projectId, userId)
        return IssueFolderListResponse(folders.list(projectId).map { it.toResponse() })
    }

    suspend fun create(projectId: Long, userId: Long, request: IssueFolderRequest): IssueFolderResponse {
        requireMember(projectId, userId)
        val name = nameOf(request.name)
        val parentId = optionalId(request.parentId, "parentId")
        val folderId = transactions.executeAndAwait {
            folders.lockProject(projectId)
            parentId?.let { requireFolder(projectId, it) }
            requireFreeName(projectId, parentId, name, null)
            folders.insert(projectId, parentId, name)
        }
        return requireFolder(projectId, folderId).toResponse()
    }

    /** 이름을 바꾸고 [IssueFolderRequest.parentId] 아래로 옮긴다. 자기 자신이나 하위 folder 아래로는 400 이다. */
    suspend fun update(projectId: Long, userId: Long, folderId: Long, request: IssueFolderRequest): IssueFolderResponse {
        requireMember(projectId, userId)
        val name = nameOf(request.name)
        val parentId = optionalId(request.parentId, "parentId")
        transactions.executeAndAwait {
            folders.lockProject(projectId)
            requireFolder(projectId, folderId)
            if (parentId != null) {
                requireFolder(projectId, parentId)
                if (folders.isSelfOrDescendant(folderId, parentId)) {
                    throw BadRequestException("folder 를 자기 자신이나 하위 folder 아래로 옮길 수 없습니다.")
                }
            }
            requireFreeName(projectId, parentId, name, folderId)
            folders.update(folderId, parentId, name)
        }
        return requireFolder(projectId, folderId).toResponse()
    }

    /** 하위 folder 와 항목은 FK cascade 로 함께 지워진다. 이슈 자체는 남는다. */
    suspend fun delete(projectId: Long, userId: Long, folderId: Long) {
        requireMember(projectId, userId)
        transactions.executeAndAwait {
            folders.lockProject(projectId)
            requireFolder(projectId, folderId)
            folders.delete(folderId)
        }
    }

    /**
     * folder 를 하위 folder 와 항목째 복제한다. 복제본은 [IssueFolderCloneRequest.parentId] 아래, 없으면 원본과 같은
     * 부모 아래([IssueFolderCloneRequest.toRoot] 면 최상위). 이름이 겹치면 ` (2)`, ` (3)` … 을 붙인다.
     */
    suspend fun clone(projectId: Long, userId: Long, folderId: Long, request: IssueFolderCloneRequest): IssueFolderResponse {
        requireMember(projectId, userId)
        val requestedParent = optionalId(request.parentId, "parentId")
        val cloneId = transactions.executeAndAwait {
            folders.lockProject(projectId)
            val source = requireFolder(projectId, folderId)
            val parentId = when {
                request.toRoot == true -> null
                requestedParent != null -> requestedParent.also {
                    requireFolder(projectId, it)
                    if (folders.isSelfOrDescendant(folderId, it)) {
                        throw BadRequestException("folder 를 자기 하위 folder 안으로 복제할 수 없습니다.")
                    }
                }
                else -> source.parentId
            }
            val base = request.name?.let(::nameOf) ?: "${source.name} 사본".take(NAME_MAX_LENGTH)
            val rootId = folders.insert(projectId, parentId, freeName(projectId, parentId, base))
            copyTree(projectId, source.id, rootId)
            rootId
        }
        return requireFolder(projectId, cloneId).toResponse()
    }

    suspend fun addItems(projectId: Long, userId: Long, folderId: Long, request: IssueFolderItemsRequest): IssueFolderResponse =
        writeItems(projectId, userId, folderId, request) { ids -> folders.addItems(folderId, ids) }

    suspend fun removeItems(projectId: Long, userId: Long, folderId: Long, request: IssueFolderItemsRequest): IssueFolderResponse =
        writeItems(projectId, userId, folderId, request) { ids -> folders.removeItems(folderId, ids) }

    private suspend fun writeItems(
        projectId: Long,
        userId: Long,
        folderId: Long,
        request: IssueFolderItemsRequest,
        write: suspend (List<Long>) -> Unit,
    ): IssueFolderResponse {
        requireMember(projectId, userId)
        val raw = request.issueIds ?: throw BadRequestException("issueIds 가 필요합니다.")
        if (raw.size > ITEMS_MAX) throw BadRequestException("이슈는 한 번에 ${ITEMS_MAX}개까지입니다.")
        val ids = raw.map { requiredId(it, "issueIds") }.distinct()
        transactions.executeAndAwait {
            folders.lockProject(projectId)
            requireFolder(projectId, folderId)
            if (folders.projectIssueIds(projectId, ids) != ids.toSet()) throw NotFoundException("이슈를 찾을 수 없습니다.")
            write(ids)
        }
        return requireFolder(projectId, folderId).toResponse()
    }

    private suspend fun copyTree(projectId: Long, sourceId: Long, targetId: Long) {
        folders.copyItems(sourceId, targetId)
        folders.children(sourceId).forEach { child ->
            val copyId = folders.insert(projectId, targetId, child.name)
            copyTree(projectId, child.id, copyId)
        }
    }

    private suspend fun freeName(projectId: Long, parentId: Long?, base: String): String {
        var name = base
        var number = 1
        while (folders.siblingNameTaken(projectId, parentId, name, null)) {
            number++
            val suffix = " ($number)"
            name = base.take(NAME_MAX_LENGTH - suffix.length) + suffix
        }
        return name
    }

    private suspend fun requireFolder(projectId: Long, folderId: Long): IssueFolderRepository.Row =
        folders.find(projectId, folderId) ?: throw NotFoundException("folder 를 찾을 수 없습니다.")

    private suspend fun requireFreeName(projectId: Long, parentId: Long?, name: String, excludeId: Long?) {
        if (folders.siblingNameTaken(projectId, parentId, name, excludeId)) {
            throw ConflictException("같은 위치에 같은 이름의 folder 가 있습니다.")
        }
    }

    private suspend fun requireMember(projectId: Long, userId: Long) {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }

    private fun nameOf(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > NAME_MAX_LENGTH) throw BadRequestException("folder 이름은 1~${NAME_MAX_LENGTH}자입니다.")
        return name
    }

    private fun optionalId(raw: String?, field: String): Long? =
        if (raw.isNullOrBlank()) null else requiredId(raw, field)

    private fun requiredId(raw: String?, field: String): Long =
        raw?.toLongOrNull()?.takeIf { it > 0 } ?: throw BadRequestException("유효하지 않은 값: $field")

    private fun IssueFolderRepository.Row.toResponse() =
        IssueFolderResponse(id.toString(), parentId?.toString(), name, issueCount)
}
