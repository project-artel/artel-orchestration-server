package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemHiddenRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemListResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemMoveRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemOrderRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemsChangeResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemsRequest
import kr.artel.orchestration.testcase.dto.parseOptionalWireId
import kr.artel.orchestration.testcase.dto.parseWireIds
import kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import kr.artel.orchestration.testcase.dto.isMapOrigin

/**
 * folder 가 담은 케이스(ARTEL-1023). 넣기 · 빼기 · 옮기기 · 순서 · 버전별 숨김과, 항목을 버전으로 푼 목록.
 *
 * 쓰기는 모두 [TestCaseFolderService.lockProjectFolders] 를 잡은 트랜잭션 안에서 한다 — folder 를 지우는 요청과
 * 겹쳐 FK 위반(500)이 나지 않게 하고, 끝 위치를 읽고 붙이는 사이에 다른 쓰기가 끼지 않게 한다.
 *
 * 비멤버, 다른 프로젝트 folder, 다른 프로젝트 build 는 404 다. 다른 프로젝트 케이스와 없는 id 는 조용히 건너뛰고
 * 세지 않는다([kr.artel.orchestration.testcase.service.TestCaseService.bulkDeleteTestCases] 와 같은 규칙).
 */
@Service
class TestCaseFolderItemService(
    private val items: TestCaseFolderItemRepository,
    private val folderService: TestCaseFolderService,
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val gameBuilds: GameBuildRepository,
    private val transactionalOperator: TransactionalOperator,
    private val projectChangeStreamManager: ProjectChangeStreamManager,
) {

    /**
     * [folderId] 의 항목을 [gameBuildId] 버전으로 푼다. 버전을 주지 않으면 프로젝트의 가장 최근 build 다.
     * 그 버전에 없는 케이스(몫이 없는 케이스, 사람이 쓴 케이스도 같다)도 줄로 내고 `inVersion=false` 로 표시한다 —
     * 화면이 빗금 줄로 그린다.
     */
    suspend fun list(projectId: Long, userId: Long, folderId: Long, gameBuildId: Long?): TestCaseFolderItemListResponse {
        folderService.requireMember(projectId, userId)
        folderService.requireFolder(projectId, folderId)
        val buildId = if (gameBuildId != null) {
            gameBuilds.findAccessibleById(gameBuildId, projectId, userId)?.id
                ?: throw NotFoundException("버전을 찾을 수 없습니다.")
        } else {
            // 가장 최근 버전. editor 행도 그 version 의 버전이다(ARTEL-1180).
            gameBuilds.findVersionsNewestFirst(projectId).firstOrNull()?.id
        }
        val rows = items.findRows(folderId, buildId).toList()
        val identities = testCases.findAllById(rows.map { it.testCaseId }).toList().associateBy { it.id }
        val here = buildId?.let { versions.findByGameBuildId(it).toList().associateBy { v -> v.testCaseId } }.orEmpty()
        val responses = rows.mapNotNull { row ->
            val identity = identities[row.testCaseId] ?: return@mapNotNull null
            val version = here[row.testCaseId]
            val map = isMapCase(identity.metadata.asString())
            // 지도 케이스는 그 버전 몫의 내용을 보인다. 몫이 없으면 정체(가장 최근 버전)의 내용이다.
            val content = version.takeIf { map }
            TestCaseFolderItemResponse(
                testCaseId = row.testCaseId.toString(),
                position = row.position,
                hidden = row.hidden,
                hiddenGameBuildIds = row.hiddenGameBuildIds.split(',').filter { it.isNotBlank() },
                inVersion = buildId == null || version != null,
                origin = if (map) MAP else MANUAL,
                scene = content?.scene ?: identity.scene,
                step = content?.step ?: identity.step,
                precondition = if (content != null) content.precondition else identity.precondition,
                expectedValue = content?.expectedValue ?: identity.expectedValue,
                status = if (content != null) content.status else identity.status,
                verificationStatus = if (buildId == null) identity.verificationStatus else version?.verificationStatus,
                createdAt = identity.createdAt ?: Instant.EPOCH,
            )
        }
        return TestCaseFolderItemListResponse(folderId.toString(), buildId?.toString(), responses)
    }

    /** 케이스를 [folderId] 끝에 넣는다. 이미 든 케이스는 그대로 두고 세지 않는다. */
    suspend fun add(projectId: Long, userId: Long, folderId: Long, request: TestCaseFolderItemsRequest) =
        write(projectId, userId, folderId) {
            Changed(items.append(projectId, folderId, parseWireIds(request.caseIds, "caseIds")).toList())
        }

    suspend fun remove(projectId: Long, userId: Long, folderId: Long, request: TestCaseFolderItemsRequest) =
        write(projectId, userId, folderId) {
            Changed(items.remove(folderId, parseWireIds(request.caseIds, "caseIds")).toList().also { items.compact(folderId) })
        }

    /**
     * [folderId] 의 항목을 다른 folder 끝으로 옮긴다. 대상에 이미 있는 케이스는 이쪽 항목만 지운다 — 대상 쪽 위치와
     * 숨김을 그대로 둔다. 같은 folder 로는 400 이다.
     */
    suspend fun move(projectId: Long, userId: Long, folderId: Long, request: TestCaseFolderItemMoveRequest) =
        write(projectId, userId, folderId) {
            val caseIds = parseWireIds(request.caseIds, "caseIds")
            val targetId = parseOptionalWireId(request.targetFolderId, "targetFolderId")
                ?: throw BadRequestException("targetFolderId 가 필요합니다.")
            if (targetId == folderId) throw BadRequestException("같은 folder 로는 옮길 수 없습니다.")
            folderService.requireFolder(projectId, targetId)
            val shared = items.findShared(folderId, targetId, caseIds).toList()
            val dropped = if (shared.isEmpty()) emptyList() else items.remove(folderId, shared).toList()
            val rest = caseIds - shared.toSet()
            val moved = if (rest.isEmpty()) emptyList() else items.move(folderId, targetId, rest).toList()
            items.compact(folderId)
            Changed(moved, dropped)
        }

    /** 순서를 통째로 다시 정한다. [TestCaseFolderItemOrderRequest.caseIds] 가 그 folder 의 항목 전부가 아니면 400 이다. */
    suspend fun reorder(projectId: Long, userId: Long, folderId: Long, request: TestCaseFolderItemOrderRequest) =
        write(projectId, userId, folderId) {
            val caseIds = parseWireIds(request.caseIds, "caseIds")
            val current = items.findCaseIds(folderId).toList()
            if (caseIds.size != current.size || caseIds.toSet() != current.toSet()) {
                throw BadRequestException("caseIds 는 이 folder 의 항목 전부여야 합니다.")
            }
            val positions = current.withIndex().associate { it.value to it.index }
            if (caseIds != current) items.reorder(folderId, caseIds)
            Changed(caseIds.filterIndexed { index, caseId -> positions[caseId] != index })
        }

    /** 버전별 숨김을 켜고 끈다. 이미 그 상태인 항목은 세지 않는다. */
    suspend fun setHidden(projectId: Long, userId: Long, folderId: Long, request: TestCaseFolderItemHiddenRequest) =
        write(projectId, userId, folderId) {
            val caseIds = parseWireIds(request.caseIds, "caseIds")
            val gameBuildId = parseOptionalWireId(request.gameBuildId, "gameBuildId")
                ?: throw BadRequestException("gameBuildId 가 필요합니다.")
            val hidden = request.hidden ?: throw BadRequestException("hidden 이 필요합니다.")
            gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
                ?: throw NotFoundException("버전을 찾을 수 없습니다.")
            Changed((if (hidden) items.hide(folderId, caseIds, gameBuildId) else items.unhide(folderId, caseIds, gameBuildId)).toList())
        }

    private suspend fun write(
        projectId: Long,
        userId: Long,
        folderId: Long,
        block: suspend () -> Changed,
    ): TestCaseFolderItemsChangeResponse {
        folderService.requireMember(projectId, userId)
        val changed = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            folderService.requireFolder(projectId, folderId)
            block()
        }
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES) // commit 뒤
        return TestCaseFolderItemsChangeResponse(
            changed = changed.caseIds.size + changed.droppedCaseIds.size,
            caseIds = changed.caseIds.map(Long::toString),
            droppedCaseIds = changed.droppedCaseIds.map(Long::toString),
        )
    }

    /** 한 쓰기에서 실제로 바뀐 케이스. */
    private data class Changed(val caseIds: List<Long>, val droppedCaseIds: List<Long> = emptyList())

    private fun isMapCase(metadata: String): Boolean = isMapOrigin(metadata)

    private companion object {
        const val MAP = "MAP"
        const val MANUAL = "MANUAL"
    }
}
