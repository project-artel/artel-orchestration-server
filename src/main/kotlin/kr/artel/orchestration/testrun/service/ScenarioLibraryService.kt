package kr.artel.orchestration.testrun.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.parseWireIds
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.dto.ImportedScenario
import kr.artel.orchestration.testrun.dto.ScenarioImportResponse
import kr.artel.orchestration.testrun.dto.ScenarioLibraryResponse
import kr.artel.orchestration.testrun.dto.ScenarioLibraryRun
import kr.artel.orchestration.testrun.dto.ScenarioLibraryScenario
import kr.artel.orchestration.testrun.dto.ScenarioLibraryVersion
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * TS 재사용(ARTEL-994): 불러오기 트리와, 고른 TS 를 TR 로 복사해 오는 것.
 *
 * **복사한다.** 원본을 같은 id 로 연결하면 한쪽에서 고친 것이 다른 버전의 TR 까지 바뀐다. 버전마다 TR 을 다시
 * 꾸리는 것이 목적이라, 앞 버전 TR 의 TS 는 그때 모습 그대로 남아야 한다.
 *
 * **복사하면서 TC 를 이번 버전 것으로 바꿔 끼운다.** #293 이후 TC 는 build 마다 다른 행이고, step 의 `case_id`
 * 는 원본 TS 가 만들어진 build 의 행을 가리킨다. 그대로 두면 이번 버전 QA 의 판정이 앞 버전 행에 쌓인다.
 * 짝은 `metadata.case_key` 로 찾고, 같은 이유로 `step_source_capability_id` 도 `capability_key` 로 바꾼다.
 */
@Service
class ScenarioLibraryService(
    private val runRepository: TestRunRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val gameBuildRepository: GameBuildRepository,
    private val testCaseRepository: TestCaseRepository,
    private val contentMapRepository: ContentMapRepository,
    private val capabilityRepository: CapabilityRepository,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
) {

    /** 비멤버면 404 다. */
    suspend fun library(projectId: Long, userId: Long): ScenarioLibraryResponse {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val scenarios = scenarioRepository.findByProjectId(projectId).toList().associateBy { requireNotNull(it.id) }
        val runs = runRepository.findByProjectIdOrderByIdDesc(projectId).toList()
        val linksByRun = runs.associate { run ->
            val id = requireNotNull(run.id)
            id to runScenarioRepository.findByTestRunIdOrderByPosition(id).toList()
        }
        val linked = linksByRun.values.flatten().map { it.testScenarioId }.toSet()

        fun runNode(runId: Long?, name: String?, scenarioIds: List<Long>) = ScenarioLibraryRun(
            testRunId = runId?.toString(),
            name = name,
            testScenarios = scenarioIds.mapNotNull { scenarios[it]?.toLibraryScenario() },
        )

        val runsByBuild = runs.groupBy { it.gameBuildId }
        val versions = gameBuildRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).toList()
            .mapNotNull { build ->
                val buildRuns = runsByBuild[build.id] ?: return@mapNotNull null
                ScenarioLibraryVersion(
                    gameBuild = build.toTestRunGameBuild(),
                    testRuns = buildRuns.map { runNode(it.id, it.name, linksByRun.getValue(it.id!!).map { l -> l.testScenarioId }) },
                )
            }
        val unversioned = runsByBuild[null].orEmpty()
            .map { runNode(it.id, it.name, linksByRun.getValue(it.id!!).map { l -> l.testScenarioId }) }
        val unlinked = scenarios.keys.filterNot { it in linked }.sortedDescending()
        val rest = unversioned + listOfNotNull(unlinked.takeIf { it.isNotEmpty() }?.let { runNode(null, null, it) })
        return ScenarioLibraryResponse(
            versions = versions + listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { ScenarioLibraryVersion(null, it) })
        )
    }

    /**
     * [rawScenarioIds] 의 TS 를 복사해 [runId] 끝에 순서대로 붙인다. TR 이 없거나 [projectId] 의 TR 이 아니거나
     * 비멤버면 404, 없는 TS 나 다른
     * 프로젝트 TS 가 섞이면 400 이다. 복사와 연결은 한 트랜잭션이다.
     */
    suspend fun importIntoRun(
        projectId: Long,
        runId: Long,
        userId: Long,
        rawScenarioIds: List<String?>?,
    ): ScenarioImportResponse {
        val run = runRepository.findById(runId)
            ?.takeIf { it.projectId == projectId && projectAccessService.isMember(projectId, userId) }
            ?: throw NotFoundException("TR 을 찾을 수 없습니다.")
        val scenarioIds = parseWireIds(rawScenarioIds, "testScenarioIds")
        val sources = scenarioRepository.findAllById(scenarioIds).toList().associateBy { requireNotNull(it.id) }
        if (sources.size != scenarioIds.size || sources.values.any { it.projectId != run.projectId }) {
            throw BadRequestException("가져올 수 없는 시나리오가 있습니다.")
        }
        val ordered = scenarioIds.map { sources.getValue(it) }
        val remap = run.gameBuildId?.let { remapFor(run.projectId, it, ordered) }

        val imported = transactionalOperator.executeAndAwait {
            var position = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
                .maxOfOrNull { it.position + 1 } ?: 0
            ordered.map { source ->
                val copy = scenarioRepository.save(
                    TestScenarioEntity(
                        projectId = source.projectId,
                        title = source.title,
                        description = source.description,
                        steps = remap?.let { rewriteSteps(source.steps, it) } ?: source.steps,
                    )
                )
                runScenarioRepository.save(
                    TestRunScenarioEntity(testRunId = runId, testScenarioId = requireNotNull(copy.id), position = position++)
                )
                ImportedScenario(
                    sourceTestScenarioId = requireNotNull(source.id).toString(),
                    testScenarioId = requireNotNull(copy.id).toString(),
                    title = copy.title,
                )
            }
        }
        return ScenarioImportResponse(
            testRunId = runId.toString(),
            importedScenarios = imported,
            unmatchedCaseIds = remap?.unmatchedCaseIds.orEmpty().sorted().map { it.toString() },
        )
    }

    /**
     * @property cases 원본 `case_id` → 이번 build 의 짝. @property capabilities 원본 capability id → 이번 build
     *   content map 의 같은 `capability_key`. @property unmatchedCaseIds `case_key` 가 있는데 이번 build 에 짝이
     *   없는 것. `case_key` 가 없는 케이스(사람이 쓴 것)는 build 에 묶이지 않아 그대로 두고 세지도 않는다.
     */
    private data class Remap(
        val cases: Map<Long, Long>,
        val capabilities: Map<Long, Long>,
        val unmatchedCaseIds: Set<Long>,
    )

    private suspend fun remapFor(projectId: Long, gameBuildId: Long, sources: List<TestScenarioEntity>): Remap {
        val steps = sources.flatMap { stepsOf(it.steps) }
        val caseIds = steps.mapNotNull { it.longOrNull(CASE_ID) }.toSet()
        val cases = if (caseIds.isEmpty()) emptyMap()
        else testCaseRepository.findTwinsInBuild(projectId, caseIds, gameBuildId).toList()
            .associate { it.sourceId to it.targetId }
        val keyed = if (caseIds.isEmpty()) emptySet()
        else testCaseRepository.findKeyedIds(projectId, caseIds).toList().toSet()

        val capabilityIds = steps.mapNotNull { it.longOrNull(CAPABILITY_ID) }.toSet()
        val targetMapId = contentMapRepository.findByGameBuildId(gameBuildId)?.id
        val capabilities = if (targetMapId == null) emptyMap() else capabilityIds.mapNotNull { id ->
            val key = capabilityRepository.findById(id)?.capabilityKey ?: return@mapNotNull null
            capabilityRepository.findByContentMapIdAndCapabilityKey(targetMapId, key)?.id?.let { id to it }
        }.toMap()
        return Remap(cases = cases, capabilities = capabilities, unmatchedCaseIds = keyed - cases.keys)
    }

    /** 알지 못하는 칸까지 그대로 옮기려고 DTO 를 거치지 않고 JSON 위에서 두 칸만 바꾼다. */
    private fun rewriteSteps(steps: Json, remap: Remap): Json {
        val array = stepsOf(steps)
        for (step in array) {
            step.longOrNull(CASE_ID)?.let { remap.cases[it] }?.let { step.put(CASE_ID, it) }
            step.longOrNull(CAPABILITY_ID)?.let { remap.capabilities[it] }?.let { step.put(CAPABILITY_ID, it) }
        }
        return Json.of(objectMapper.writeValueAsString(objectMapper.createArrayNode().addAll(array)))
    }

    private fun stepsOf(steps: Json): List<ObjectNode> =
        (objectMapper.readTree(steps.asString()) as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()

    private fun ObjectNode.longOrNull(field: String): Long? =
        get(field)?.takeIf { it.canConvertToLong() && !it.isNull }?.asLong()

    private fun TestScenarioEntity.toLibraryScenario(): ScenarioLibraryScenario {
        val parsed = stepsOf(steps)
        return ScenarioLibraryScenario(
            testScenarioId = requireNotNull(id).toString(),
            title = title,
            stepCount = parsed.size,
            caseCount = parsed.mapNotNull { it.longOrNull(CASE_ID) }.toSet().size,
            updatedAt = updatedAt,
        )
    }

    private companion object {
        const val CASE_ID = "case_id"
        const val CAPABILITY_ID = "step_source_capability_id"
    }
}
