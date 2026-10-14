package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseFolderCloneRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderListResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderResponse
import kr.artel.orchestration.testcase.dto.parseOptionalWireId
import kr.artel.orchestration.testcase.entity.TestCaseFolderEntity
import kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository
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
    private val items: TestCaseFolderItemRepository,
    private val gameBuilds: GameBuildRepository,
) {

    /**
     * folder 목록. [gameBuildId] 를 주면 folder 마다 그 버전에서 보이는 항목 수를 함께 센다(ARTEL-1023).
     * 다른 프로젝트 build 면 404 다.
     */
    suspend fun list(projectId: Long, userId: Long, gameBuildId: Long? = null): TestCaseFolderListResponse {
        requireMember(projectId, userId)
        if (gameBuildId != null) {
            gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
                ?: throw NotFoundException("버전을 찾을 수 없습니다.")
        }
        return TestCaseFolderListResponse(
            folders.findRowsByProjectId(projectId, gameBuildId).map { it.toResponse() }.toList()
        )
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

    /**
     * folder 를 지운다. 하위 folder 는 FK cascade 로 함께 지워진다(V99). 항목도 cascade 로 지워지고 케이스 자체는
     * 남는다 — 다른 folder 에 없으면 folder 없음이 된다(V105).
     */
    suspend fun delete(projectId: Long, userId: Long, folderId: Long) {
        requireMember(projectId, userId)
        transactionalOperator.executeAndAwait {
            lockProjectFolders(projectId)
            folders.delete(requireFolder(projectId, folderId))
        }
    }

    /**
     * folder 를 하위 folder 와 항목째 복제한다(ARTEL-1023). 항목의 순서와 버전별 숨김도 따라간다.
     *
     * 복제본은 [TestCaseFolderCloneRequest.parentId] 아래, 없으면 원본과 같은 부모 아래에 둔다
     * ([TestCaseFolderCloneRequest.toRoot] 면 최상위). 자기 하위 folder 안으로는 400 이다 — 복제하면서 늘어나는
     * 트리를 다시 복제하게 된다. 이름이 겹치면 ` (2)`, ` (3)` … 중 처음 빈 이름을 쓴다. 하위 folder 는 원본 이름
     * 그대로다(부모가 새것이라 겹칠 수 없다).
     */
    suspend fun clone(
        projectId: Long,
        userId: Long,
        folderId: Long,
        request: TestCaseFolderCloneRequest,
    ): TestCaseFolderResponse {
        requireMember(projectId, userId)
        val requestedParentId = parseOptionalWireId(request.parentId, "parentId")
        val cloneId = transactionalOperator.executeAndAwait {
            lockProjectFolders(projectId)
            val source = requireFolder(projectId, folderId)
            val parentId = when {
                request.toRoot == true -> null
                requestedParentId != null -> requestedParentId.also {
                    requireFolder(projectId, it)
                    if (folders.isSelfOrDescendant(folderId, it)) {
                        throw BadRequestException("folder 를 자기 하위 folder 안으로 복제할 수 없습니다.")
                    }
                }
                else -> source.parentId
            }
            val baseName = request.name?.let(::folderNameOf) ?: "${source.name} 사본".take(NAME_MAX_LENGTH)
            val root = folders.save(
                TestCaseFolderEntity(projectId = projectId, parentId = parentId, name = freeName(projectId, parentId, baseName))
            )
            copyTree(source, requireNotNull(root.id))
            root.id!!
        }
        return responseOf(cloneId)
    }

    /** [source] 의 항목과 하위 트리를 [targetId] 아래로 복사한다. */
    private suspend fun copyTree(source: TestCaseFolderEntity, targetId: Long) {
        val sourceId = requireNotNull(source.id)
        items.copyAll(sourceId, targetId)
        folders.findByParentIdOrderByIdAsc(sourceId).toList().forEach { child ->
            val copy = folders.save(TestCaseFolderEntity(projectId = source.projectId, parentId = targetId, name = child.name))
            copyTree(child, requireNotNull(copy.id))
        }
    }

    /**
     * [parentId] 아래에 이름이 겹치지 않는 folder 를 만든다. **[lockProjectFolders] 를 잡은 트랜잭션 안에서 불러야
     * 한다.** 버전을 folder 로 복제할 때 쓴다(ARTEL-1023).
     */
    suspend fun createWithFreeName(projectId: Long, parentId: Long?, baseName: String): TestCaseFolderEntity {
        parentId?.let { requireFolder(projectId, it) }
        val name = freeName(projectId, parentId, baseName.take(NAME_MAX_LENGTH))
        return folders.save(TestCaseFolderEntity(projectId = projectId, parentId = parentId, name = name))
    }

    /** 같은 부모 아래 비어 있는 이름. [baseName] 이 겹치면 ` (2)`, ` (3)` … 을 붙인다. */
    private suspend fun freeName(projectId: Long, parentId: Long?, baseName: String): String {
        var name = baseName
        var suffixNumber = 1
        while (folders.existsSiblingName(projectId, parentId, name, null)) {
            suffixNumber++
            val suffix = " ($suffixNumber)"
            name = baseName.take(NAME_MAX_LENGTH - suffix.length) + suffix
        }
        return name
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

    /** 비멤버면 404 다. folder 항목 서비스도 같은 확인을 쓴다. */
    suspend fun requireMember(projectId: Long, userId: Long) {
        if (!projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        }
    }

    suspend fun responseOf(folderId: Long): TestCaseFolderResponse =
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
