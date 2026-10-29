package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesChangeResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesRequest
import kr.artel.orchestration.testcase.dto.originLabel
import kr.artel.orchestration.testcase.dto.parseWireIds
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 사용자 생성 케이스를 버전에 넣고 빼고, 다음 버전으로 이어 넣는다(ARTEL-1031).
 *
 * 지도가 낸 케이스는 생성할 때 그 버전의 몫이 생긴다. 사용자 생성 케이스에는 그런 계기가 없어, QA 판정을 받기 전에는
 * 어느 버전에도 없고 새 버전이 와도 따라오지 않았다. 두 길을 연다.
 * - 사람이 고른 케이스를 특정 버전에 넣는다([addToVersion]).
 * - 새 build 의 TC 를 생성할 때, 바로 앞 버전에 있던 사용자 생성 케이스를 이어 넣는다([carryOver]).
 *
 * 넣은 몫은 정체의 지금 내용으로 만든다. 상태는 지도 케이스의 새 몫과 같은 규칙이다 — 앞 버전에서 판정이 났으면
 * (`VERIFIED` · `FAILED` · `REGRESSION`) `REGRESSION`, 아니면 `DRAFT`.
 *
 * QA 실행 전 전체 추가에서 `includeGenerated`를 지정하면 지도 케이스도 넣는다. 자동 이월과 삭제는 사용자 생성 케이스만 다룬다.
 */
@Service
class ManualCaseVersionService(
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val gameBuilds: GameBuildRepository,
    private val folderService: TestCaseFolderService,
    private val transactionalOperator: TransactionalOperator,
    private val projectChangeStreamManager: ProjectChangeStreamManager,
) {

    /** 아직 이 버전에 없는 케이스를 넣는다. `includeGenerated`는 지도 케이스도 포함한다. 비멤버 · 다른 프로젝트 build는 404. */
    suspend fun addToVersion(
        projectId: Long,
        userId: Long,
        gameBuildId: Long,
        request: TestCaseVersionCasesRequest,
    ): TestCaseVersionCasesChangeResponse {
        requireBuild(projectId, userId, gameBuildId)
        val caseIds = parseWireIds(request.caseIds, "caseIds")
        val added = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            insert(projectId, gameBuildId, caseIds, request.includeGenerated)
        }
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES) // commit 뒤
        return TestCaseVersionCasesChangeResponse(added.map(Long::toString))
    }

    /** 사용자 생성 케이스를 이 버전에서 뺀다. 그 버전에서 받은 판정도 함께 지워진다. */
    suspend fun removeFromVersion(
        projectId: Long,
        userId: Long,
        gameBuildId: Long,
        request: TestCaseVersionCasesRequest,
    ): TestCaseVersionCasesChangeResponse {
        requireBuild(projectId, userId, gameBuildId)
        val caseIds = parseWireIds(request.caseIds, "caseIds")
        val removed = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val manual = testCases.findAllById(caseIds).toList()
                .filter { it.projectId == projectId && it.originLabel() == MANUAL }
                .mapNotNull { it.id }
            val rows = manual.mapNotNull { versions.findByTestCaseIdAndGameBuildId(it, gameBuildId) }
            rows.forEach { versions.delete(it) }
            val removedIds = rows.map { it.testCaseId }
            if (removedIds.isNotEmpty()) versions.syncIdentities(removedIds)
            removedIds
        }
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES) // commit 뒤
        return TestCaseVersionCasesChangeResponse(removed.map(Long::toString))
    }

    /**
     * 바로 앞 버전에 있던 사용자 생성 케이스를 [gameBuildId] 버전에 이어 넣고 넣은 수를 돌려준다. TC 생성 트랜잭션 안에서
     * 부른다. 앞 버전에서 사람이 일부러 뺀 케이스는 이어지지 않는다 — 앞 버전에 없기 때문이다.
     */
    suspend fun carryOver(projectId: Long, gameBuildId: Long): Int {
        val previous = versions.findPreviousBuildId(gameBuildId) ?: return 0
        val caseIds = versions.findManualCaseIdsToCarry(previous, gameBuildId).toList()
        if (caseIds.isEmpty()) return 0
        return insert(projectId, gameBuildId, caseIds).size
    }

    /**
     * 막 만든 사용자 생성 케이스를 [gameBuildId] 버전에 넣는다(ARTEL-1031). 케이스 생성 트랜잭션 안에서 부른다.
     * build 가 이 프로젝트 것인지는 부르는 쪽이 확인한다.
     */
    suspend fun attachNewCase(projectId: Long, gameBuildId: Long, testCaseId: Long) {
        insert(projectId, gameBuildId, listOf(testCaseId))
    }

    /** 몫을 만든다. lock 을 잡은 트랜잭션 안에서 불러야 한다. */
    private suspend fun insert(
        projectId: Long,
        gameBuildId: Long,
        caseIds: Collection<Long>,
        includeGenerated: Boolean = false,
    ): List<Long> {
        val candidates = testCases.findAllById(caseIds).toList()
            .filter { it.projectId == projectId && (includeGenerated || it.originLabel() == MANUAL) }
            .filter { versions.findByTestCaseIdAndGameBuildId(requireNotNull(it.id), gameBuildId) == null }
        if (candidates.isEmpty()) return emptyList()
        val ids = candidates.mapNotNull { it.id }
        val previous = versions.findPreviousStatuses(ids, gameBuildId).toList()
            .associate { it.testCaseId to it.verificationStatus }
        candidates.forEach { identity ->
            val start = if (previous[identity.id] in JUDGED) VerificationStatus.REGRESSION else VerificationStatus.DRAFT
            versions.save(TestCaseVersionEntity.snapshotOf(identity, gameBuildId).copy(verificationStatus = start.name))
        }
        versions.syncIdentities(ids)
        return ids
    }

    private suspend fun requireBuild(projectId: Long, userId: Long, gameBuildId: Long) {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("버전을 찾을 수 없습니다.")
    }

    private companion object {
        const val MANUAL = "MANUAL"
        val JUDGED = setOf(
            VerificationStatus.VERIFIED.name,
            VerificationStatus.FAILED.name,
            VerificationStatus.REGRESSION.name,
        )
    }
}
