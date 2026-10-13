package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseFolderListResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderResponse
import kr.artel.orchestration.testcase.dto.parseOptionalWireId
import kr.artel.orchestration.testcase.entity.TestCaseFolderEntity
import kr.artel.orchestration.testcase.repository.TestCaseFolderRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * test case folder 의 목록 · 생성 · 수정 · 삭제(ARTEL-969).
 *
 * ## 한 프로젝트의 folder 쓰기는 advisory lock 으로 한 번에 하나씩 실행한다
 *
 * 이름 중복 확인과 순환 확인은 읽은 뒤에 쓰므로, 두 요청이 동시에 오면 둘 다 확인을 지나친다 —
 * A 를 B 아래로, B 를 A 아래로 옮기는 두 요청이 순환을 만든다. 그래서 folder 를 쓰거나 folder 를
 * 가리키는 쓰기(test case 이동, folder 를 지정한 test case 생성)는 트랜잭션 안에서 먼저
 * [lockProjectFolders] 를 잡는다. 같은 lock 을 delete 도 잡으므로 확인한 folder 가 쓰기 전에 사라져
 * FK 위반(500)이 나는 일도 없다.
 *
 * 비멤버와 다른 프로젝트 folder 는 404 다.
 */
@Service
class TestCaseFolderService(
    private val folders: TestCaseFolderRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
    private val databaseClient: DatabaseClient,
) {

    suspend fun list(projectId: Long, userId: Long): TestCaseFolderListResponse {
        requireMember(projectId, userId)
        return TestCaseFolderListResponse(folders.findRowsByProjectId(projectId).map { it.toResponse() }.toList())
    }

    suspend fun create(projectId: Long, userId: Long, request: TestCaseFolderRequest): TestCaseFolderResponse {
        requireMember(projectId, userId)
        val name = folderNameOf(request.name)
        val parentId = parseOptionalWireId(request.parentId, "parentId")
        val saved = transactionalOperator.executeAndAwait {
            lockProjectFolders(projectId)
            parentId?.let { requireFolder(projectId, it) }
            requireFreeName(projectId, parentId, name, excludeId = null)
            folders.save(TestCaseFolderEntity(projectId = projectId, parentId = parentId, name = name))
        }
        return responseOf(saved.id!!)
    }

    /** 이름을 바꾸고 [TestCaseFolderRequest.parentId] 아래로 옮긴다. 자기 자신이나 하위 folder 아래로는 400 이다. */
    suspend fun update(
        projectId: Long,
        userId: Long,
        folderId: Long,
        request: TestCaseFolderRequest,
    ): TestCaseFolderResponse {
        requireMember(projectId, userId)
        val name = folderNameOf(request.name)
        val parentId = parseOptionalWireId(request.parentId, "parentId")
        transactionalOperator.executeAndAwait {
            lockProjectFolders(projectId)
            val folder = requireFolder(projectId, folderId)
            if (parentId != null) {
                requireFolder(projectId, parentId)
                if (folders.isSelfOrDescendant(folderId, parentId)) {
                    throw BadRequestException("folder 를 자기 자신이나 하위 folder 아래로 옮길 수 없습니다.")
                }
            }
            requireFreeName(projectId, parentId, name, excludeId = folderId)
            folders.save(folder.copy(parentId = parentId, name = name))
        }
        return responseOf(folderId)
    }

    /** folder 를 지운다. 하위 folder 는 FK cascade 로 함께 지워지고, 그 안의 test case 는 folder 없음이 된다(V99). */
    suspend fun delete(projectId: Long, userId: Long, folderId: Long) {
        requireMember(projectId, userId)
        transactionalOperator.executeAndAwait {
            lockProjectFolders(projectId)
            folders.delete(requireFolder(projectId, folderId))
        }
    }

    /** 이 프로젝트의 folder 를 돌려준다. 없거나 다른 프로젝트 것이면 404 다. */
    suspend fun requireFolder(projectId: Long, folderId: Long): TestCaseFolderEntity =
        folders.findByIdAndProjectId(folderId, projectId)
            ?: throw NotFoundException("folder 를 찾을 수 없습니다.")

    /**
     * 이 프로젝트의 folder 쓰기를 한 번에 하나씩 실행하게 하는 `pg_advisory_xact_lock`. **트랜잭션 안에서 불러야 한다** —
     * 밖에서 부르면 그 문장 하나 동안만 잡혀 아무것도 막지 못한다([kr.artel.orchestration.auth.service.FirstUserGate]
     * 와 같은 이유).
     *
     * 키는 `'test_case_folder:<projectId>'` 의 64비트 hash 다. namespace 문자열을 붙여 다른 advisory lock
     * 과 겹치지 않게 한다.
     */
    suspend fun lockProjectFolders(projectId: Long) {
        databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended('test_case_folder:' || :projectId, 0))")
            .bind("projectId", projectId)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 같은 부모 아래 이름이 겹치면 409. 이름은 `trim()` 한 값을 대소문자 구분해 비교한다. */
    private suspend fun requireFreeName(projectId: Long, parentId: Long?, name: String, excludeId: Long?) {
        if (folders.existsSiblingName(projectId, parentId, name, excludeId)) {
            throw ConflictException("같은 위치에 같은 이름의 folder 가 있습니다.")
        }
    }

    private suspend fun requireMember(projectId: Long, userId: Long) {
        if (!projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        }
    }

    private suspend fun responseOf(folderId: Long): TestCaseFolderResponse =
        requireNotNull(folders.findRowById(folderId)) { "방금 쓴 folder $folderId 를 다시 읽지 못했다" }.toResponse()

    private fun folderNameOf(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > NAME_MAX_LENGTH) {
            throw BadRequestException("folder 이름은 1자 이상 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
        }
        return name
    }

    companion object {
        /** `test_case_folder.name` 의 `VARCHAR(200)` 과 같다. */
        const val NAME_MAX_LENGTH = 200
    }
}
