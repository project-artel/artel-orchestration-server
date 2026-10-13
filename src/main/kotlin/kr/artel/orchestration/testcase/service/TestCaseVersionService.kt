package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionListResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionRow
import kr.artel.orchestration.testcase.dto.TestCaseVersionSummary
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 케이스 라이브러리를 게임 버전별로 보는 조회(ARTEL-1021).
 *
 * 지도가 낸 케이스는 버전마다 몫(`test_case_version`)이 있고, 몫이 없으면 그 버전에 없는 케이스다. 사람이 쓴
 * 케이스는 지도와 무관하게 모든 버전에 있다고 본다 — 그 버전에서 판정을 받은 적 없으면 `DRAFT` 다.
 */
@Service
class TestCaseVersionService(
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val gameBuilds: GameBuildRepository,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
) {

    /** 버전 목록(최신 순)과 버전별 수. 비멤버면 404 다. */
    suspend fun versions(projectId: Long, userId: Long): TestCaseVersionListResponse {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val builds = gameBuilds.findAllForMember(projectId, userId).toList()
        val counts = versions.countByBuild(projectId).toList().groupBy { it.gameBuildId }
        val manualTotal = testCases.countManualByProjectId(projectId).toInt()
        val items = builds.mapIndexed { index, build ->
            val byStatus = VerificationStatus.entries.associate { it.name to 0 }.toMutableMap()
            var manualWithVersion = 0
            counts[build.id].orEmpty().forEach { row ->
                byStatus.merge(row.verificationStatus, row.total.toInt(), Int::plus)
                if (row.manual) manualWithVersion += row.total.toInt()
            }
            byStatus.merge(VerificationStatus.DRAFT.name, manualTotal - manualWithVersion, Int::plus)
            TestCaseVersionSummary(
                gameBuildId = requireNotNull(build.id).toString(),
                version = build.version,
                label = build.label,
                createdAt = build.createdAt,
                latest = index == 0,
                total = byStatus.values.sum(),
                counts = byStatus,
            )
        }
        return TestCaseVersionListResponse(items)
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
        val here = versions.findByGameBuildId(gameBuildId).toList().associateBy { it.testCaseId }
        val there = compareTo?.let { versions.findByGameBuildId(it).toList().associateBy { v -> v.testCaseId } }.orEmpty()
        val items = testCases.findByProjectIdOrderByIdDesc(projectId).toList().mapNotNull { identity ->
            val id = requireNotNull(identity.id)
            if (isMapCase(identity)) mapRow(identity, here[id], there[id], compareTo != null)
            else manualRow(identity, here[id], there[id], compareTo != null)
        }
        return TestCaseVersionCasesResponse(
            gameBuildId = gameBuildId.toString(),
            compareToGameBuildId = compareTo?.toString(),
            items = items,
        )
    }

    private fun mapRow(
        identity: TestCaseEntity,
        here: TestCaseVersionEntity?,
        there: TestCaseVersionEntity?,
        comparing: Boolean,
    ): TestCaseVersionRow? {
        val content = here ?: there?.takeIf { comparing } ?: return null
        val presence = when {
            !comparing || (here != null && there != null) -> BOTH
            here != null -> ONLY_THIS
            else -> ONLY_OTHER
        }
        return TestCaseVersionRow(
            id = requireNotNull(identity.id).toString(),
            scene = content.scene,
            step = content.step,
            precondition = content.precondition,
            expectedValue = content.expectedValue,
            status = content.status,
            origin = MAP,
            folderId = identity.folderId?.toString(),
            createdAt = identity.createdAt ?: Instant.EPOCH,
            verificationStatus = here?.verificationStatus,
            presence = presence,
            compareVerificationStatus = there?.verificationStatus?.takeIf { comparing },
        )
    }

    private fun manualRow(
        identity: TestCaseEntity,
        here: TestCaseVersionEntity?,
        there: TestCaseVersionEntity?,
        comparing: Boolean,
    ) = TestCaseVersionRow(
        id = requireNotNull(identity.id).toString(),
        scene = identity.scene,
        step = identity.step,
        precondition = identity.precondition,
        expectedValue = identity.expectedValue,
        status = identity.status,
        origin = MANUAL,
        folderId = identity.folderId?.toString(),
        createdAt = identity.createdAt ?: Instant.EPOCH,
        verificationStatus = here?.verificationStatus ?: VerificationStatus.DRAFT.name,
        presence = BOTH,
        compareVerificationStatus = if (comparing) there?.verificationStatus ?: VerificationStatus.DRAFT.name else null,
    )

    private fun isMapCase(identity: TestCaseEntity): Boolean =
        runCatching { objectMapper.readTree(identity.metadata.asString()) }.getOrNull()
            ?.path(MapTestCaseWriter.ORIGIN_FIELD)?.asText() == MapTestCaseWriter.ORIGIN

    private companion object {
        const val MAP = "MAP"
        const val MANUAL = "MANUAL"
        const val BOTH = "BOTH"
        const val ONLY_THIS = "ONLY_THIS"
        const val ONLY_OTHER = "ONLY_OTHER"
    }
}
