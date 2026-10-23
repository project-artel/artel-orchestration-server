package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionListResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionRow
import kr.artel.orchestration.testcase.dto.TestCaseVersionSummary
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionGroupRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service
import java.time.Instant
import kr.artel.orchestration.testcase.dto.CaseListFields
import kr.artel.orchestration.testcase.dto.VersionListFields

/**
 * 케이스 라이브러리를 게임 버전별로 보는 조회(ARTEL-1021).
 *
 * 케이스는 그 버전에 몫(`test_case_version`)이 있어야 그 버전에 있다. 지도가 낸 케이스는 생성할 때, 사람이 쓴 케이스는
 * 그 버전에서 QA 판정을 받을 때 몫이 생긴다. 어느 버전에도 몫이 없는 케이스는 "버전 없음" 에 모인다(ARTEL-1023) —
 * 예전에는 사람이 쓴 케이스를 모든 버전에 `DRAFT` 로 끼워 넣어, 버전마다 같은 줄이 되풀이됐다.
 */
@Service
class TestCaseVersionService(
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val gameBuilds: GameBuildRepository,
    private val projectAccessService: ProjectAccessService,
    private val folderItems: TestCaseFolderItemRepository,
    private val groups: TestCaseVersionGroupRepository,
    private val reader: TestCaseReader,
) {

    /**
     * 버전 목록(최신 순)과 버전별 수, 버전 묶음, "버전 없음" 수. 비멤버면 404 다.
     * editor 행도 그 version 의 버전이다(ARTEL-1180). `isEditor` 로 같은 version 의 build 행과 구분한다.
     */
    suspend fun versions(projectId: Long, userId: Long): TestCaseVersionListResponse {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val builds = gameBuilds.findAllForMember(projectId, userId).toList()
        val counts = versions.countByBuild(projectId).toList().groupBy { it.gameBuildId }
        val settings = groups.findSettings(projectId).toList().associateBy { it.gameBuildId }
        val items = builds.mapIndexed { index, build ->
            val byStatus = VerificationStatus.entries.associate { it.name to 0 }.toMutableMap()
            counts[build.id].orEmpty().forEach { row -> byStatus.merge(row.verificationStatus, row.total.toInt(), Int::plus) }
            val setting = settings[build.id]
            TestCaseVersionSummary(
                gameBuildId = requireNotNull(build.id).toString(),
                version = build.version,
                label = build.label,
                createdAt = build.createdAt,
                isEditor = build.isEditor,
                latest = index == 0,
                total = byStatus.values.sum(),
                counts = byStatus,
                groupId = setting?.groupId?.toString(),
                hidden = setting?.hidden ?: false,
            )
        }
        val versioned = versions.findVersionedCaseIds(projectId).toList().toSet()
        val unversioned = testCases.findByProjectIdOrderByIdDesc(projectId).toList().count { it.id !in versioned }
        return TestCaseVersionListResponse(
            items = items,
            groups = groups.findByProjectIdOrderByNameAscIdAsc(projectId).toList()
                .map { TestCaseVersionGroupResponse(requireNotNull(it.id).toString(), it.name) },
            unversionedTotal = unversioned,
        )
    }

    /**
     * [gameBuildId] 버전의 케이스 표. [compareTo] 를 주면 그 버전의 판정과 존재 여부를 같은 줄에 싣고, 그 버전에만
     * 있는 케이스도 줄로 낸다. 버전이 이 프로젝트 것이 아니거나 비멤버면 404 다.
     */
    suspend fun cases(projectId: Long, gameBuildId: Long, compareTo: Long?, userId: Long): TestCaseVersionCasesResponse {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("버전을 찾을 수 없습니다.")
        if (compareTo != null) {
            gameBuilds.findAccessibleById(compareTo, projectId, userId) ?: throw NotFoundException("비교할 버전을 찾을 수 없습니다.")
        }
        // 목록에 보일 칸만 읽는다(ARTEL-1212). 행 전체를 읽으면 쓰지 않는 JSON 칸까지 2천여 줄 × 2 테이블이었다.
        val here = versions.findListFieldsByGameBuildId(gameBuildId).toList().associateBy { it.testCaseId }
        val there = compareTo?.let { versions.findListFieldsByGameBuildId(it).toList().associateBy { v -> v.testCaseId } }.orEmpty()
        val folderIds = folderItems.findFirstFolders(projectId).toList().associate { it.testCaseId to it.folderId }
        val items = testCases.findListFieldsByProjectIdOrderByIdDesc(projectId).toList().mapNotNull { case ->
            rowOf(case, here[case.id], there[case.id], compareTo != null, folderIds[case.id])
        }
        return TestCaseVersionCasesResponse(
            gameBuildId = gameBuildId.toString(),
            compareToGameBuildId = compareTo?.toString(),
            items = items,
        )
    }

    /** "버전 없음": 어느 버전에도 몫이 없는 케이스(ARTEL-1023). 판정 칸은 케이스의 상태다. */
    suspend fun unversioned(projectId: Long, userId: Long): TestCaseVersionCasesResponse {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val versioned = versions.findVersionedCaseIds(projectId).toList().toSet()
        val folderIds = folderItems.findFirstFolders(projectId).toList().associate { it.testCaseId to it.folderId }
        val items = testCases.findListFieldsByProjectIdOrderByIdDesc(projectId).toList()
            .filter { it.id !in versioned }
            .map { case ->
                TestCaseVersionRow(
                    id = case.id.toString(),
                    scene = case.scene,
                    step = case.step,
                    precondition = case.precondition,
                    expectedValue = case.expectedValue,
                    status = case.status,
                    origin = if (case.fromMap) MAP else MANUAL,
                    folderId = folderIds[case.id]?.toString(),
                    createdAt = case.createdAt ?: Instant.EPOCH,
                    verificationStatus = case.verificationStatus,
                    presence = BOTH,
                    compareVerificationStatus = null,
                )
            }
        return TestCaseVersionCasesResponse(gameBuildId = null, compareToGameBuildId = null, items = items)
    }

    /**
     * 한 줄. 이 버전에 몫이 없으면 비교할 때만(비교 버전에 몫이 있으면) 줄이 된다. 내용은 지도 케이스면 그 버전의 몫,
     * 사람이 쓴 케이스면 정체(사람이 고친 내용)다.
     */
    private fun rowOf(
        case: CaseListFields,
        here: VersionListFields?,
        there: VersionListFields?,
        comparing: Boolean,
        folderId: Long?,
    ): TestCaseVersionRow? {
        val version = here ?: there?.takeIf { comparing } ?: return null
        val presence = when {
            !comparing || (here != null && there != null) -> BOTH
            here != null -> ONLY_THIS
            else -> ONLY_OTHER
        }
        // 내용을 비추는 규칙은 TR · QA 와 같은 한 자리다(ARTEL-1069). 목록은 칸만 읽은 행이라 그 규칙의 목록판을 쓴다.
        val shown = reader.projectListRow(case, version)
        return TestCaseVersionRow(
            id = case.id.toString(),
            scene = shown.scene,
            step = shown.step,
            precondition = shown.precondition,
            expectedValue = shown.expectedValue,
            status = shown.status,
            origin = if (case.fromMap) MAP else MANUAL,
            folderId = folderId?.toString(),
            createdAt = case.createdAt ?: Instant.EPOCH,
            verificationStatus = here?.verificationStatus,
            presence = presence,
            compareVerificationStatus = there?.verificationStatus?.takeIf { comparing },
        )
    }

    private companion object {
        const val MAP = "MAP"
        const val MANUAL = "MANUAL"
        const val BOTH = "BOTH"
        const val ONLY_THIS = "ONLY_THIS"
        const val ONLY_OTHER = "ONLY_OTHER"
    }
}
