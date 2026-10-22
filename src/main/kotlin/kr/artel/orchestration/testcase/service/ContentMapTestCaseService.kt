package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.ContentMapTestCaseGenerationResponse
import kr.artel.orchestration.testcase.dto.TestCaseGenerationCandidate
import kr.artel.orchestration.testcase.dto.TestCaseGenerationCandidatesResponse
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * build 하나의 content map 에서 test case 를 생성한다(ARTEL-971).
 *
 * 예전에는 content map 적재가 끝날 때마다 [MapTestCaseWriter.rewrite] 가 돌아 test case 가 사용자 몰래
 * 생기고 바뀌고 지워졌다. 이제 이 서비스의 두 경로로만 돈다:
 * - [generate] — `POST /api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases`. 사용자가 누른다.
 * - [regenerate] — `/internal/test-cases/content-maps/{contentMapId}/regenerate`. 생성기를 고친 뒤 개발자가 부른다.
 *
 * 두 경로 모두 한 트랜잭션 안에서 [MapTestCaseWriter.rewrite] 를 부른다. 생성한 케이스를 folder 에 넣지
 * 않는다 — build 별 케이스는 버전 목록이 보인다(ARTEL-1023). 프로젝트 folder lock 은 그대로 잡는다. 같은 build 를
 * 두 요청이 동시에 생성하는 것을 그 lock 이 한 줄로 세운다.
 */
@Service
class ContentMapTestCaseService(
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val documents: ContentMapDocumentRepository,
    private val folderService: TestCaseFolderService,
    private val writer: MapTestCaseWriter,
    private val manualCases: ManualCaseVersionService,
    private val verdictCarry: VerdictCarryOverService,
    private val versions: TestCaseVersionRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
    private val projectChangeStreamManager: ProjectChangeStreamManager,
) {

    private val logger = LoggerFactory.getLogger(ContentMapTestCaseService::class.java)

    /**
     * build 가 없거나 비멤버이거나 다른 프로젝트 build 면 404 다 — 셋을 구분하지 않는다
     * ([kr.artel.orchestration.contentmap.service.ContentMapViewService.read] 와 같은 확인).
     * build 에 content map 이 없거나 적재를 마친 문서가 없으면 409 다. editor 행도 그 version 의 버전이라 만들 수 있다
     * (ARTEL-1180).
     *
     * 이미 지도에서 TC 를 만든 build 도 409(`TEST_CASES_ALREADY_GENERATED`)다(ARTEL-1040). [overwrite] 면 막지 않고
     * 그 버전의 지도 TC 를 지금 지도로 덮어쓴다(ARTEL-1041) — 남는 TC 의 판정은 그대로이고, 지도에서 사라진 TC 는 그
     * 판정과 함께 빠지며, 사용자 생성 TC 는 건드리지 않는다. 화면이 경고를 보인 뒤에만 보낸다.
     *
     * [carryOver] 가 false 면 앞 버전의 사용자 생성 케이스를 이어 넣지 않고 `carriedOver` 는 0 이다.
     *
     * [verdicts] 를 주면 생성 뒤 앞 버전 판정을 그 방식으로 이어받는다([VerdictCarryOverService], ARTEL-1180).
     * `SELECTED` 는 [selected] 의 케이스만이다. 주지 않으면 지금 규칙 그대로다.
     */
    suspend fun generate(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        carryOver: Boolean = true,
        overwrite: Boolean = false,
        verdicts: VerdictCarryOverService.Mode? = null,
        selected: Set<Long> = emptySet(),
    ): ContentMapTestCaseGenerationResponse {
        val build = gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        return generateFor(build, carryOver, overwrite, verdicts, selected)
    }

    /**
     * 생성 뒤 판정을 가져온다(ARTEL-1180). 규칙은 [VerdictCarryOverService.carry] 에 있다.
     *
     * [fromGameBuildId] 는 가져올 버전이다 — 없으면 케이스마다 바로 앞 버전. [testCaseIds] 가 null 이면 이 버전의 모든
     * TC 다. 둘 다 없거나 [testCaseIds] 가 빈 목록이면 400, 가져올 버전이 이 버전 자신이어도 400 이다. 두 build 중
     * 하나라도 없거나 비멤버 · 다른 프로젝트면 404 다.
     */
    suspend fun carryVerdicts(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        testCaseIds: List<Long>?,
        fromGameBuildId: Long? = null,
    ): VerdictCarryOverService.Outcome {
        if (testCaseIds?.isEmpty() == true) throw BadRequestException("testCaseIds 가 비어 있습니다.")
        if (testCaseIds == null && fromGameBuildId == null) throw BadRequestException("testCaseIds 나 fromGameBuildId 가 필요합니다.")
        if (fromGameBuildId == gameBuildId) throw BadRequestException("같은 버전에서는 가져올 수 없습니다.")
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        if (fromGameBuildId != null) {
            gameBuilds.findAccessibleById(fromGameBuildId, projectId, userId) ?: throw NotFoundException("가져올 버전을 찾을 수 없습니다.")
        }
        val outcome = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            verdictCarry.carry(gameBuildId, testCaseIds, fromGameBuildId)
        }
        if (outcome.carried.isNotEmpty()) projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES)
        return outcome
    }

    /**
     * 스캔이 끝난 뒤 그 build 의 TC 를 만든다(ARTEL-1040). 멤버 확인은 스캔을 시킬 때 이미 했다. 규칙은 [generate] 와
     * 같아서, 막히면 같은 409 를 던진다. 판정 이어받기는 하지 않는다 — 스캔을 시키는 자리에서 고르지 않는다.
     */
    suspend fun generateAfterScan(gameBuildId: Long, carryOver: Boolean): ContentMapTestCaseGenerationResponse {
        val build = gameBuilds.findById(gameBuildId) ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        return generateFor(build, carryOver)
    }

    /**
     * 이 build 에 지금 TC 를 만들 수 있는지 미리 본다. 지도는 보지 않는다 — 스캔을 시키는 자리에서 쓰고, 지도는 그
     * 스캔이 만든다. 이미 만든 build 면 [generate] 와 같은 409 다.
     */
    suspend fun requireGeneratable(build: GameBuildEntity) {
        if (versions.existsMapCasesByGameBuildId(requireNotNull(build.id))) throw alreadyGenerated()
    }

    /** 라이브러리 모달의 후보. 버전 최신 순이고, editor 행도 있다(ARTEL-1180). 비멤버면 404 다. */
    suspend fun candidates(userId: Long, projectId: Long): TestCaseGenerationCandidatesResponse {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val items = gameBuilds.findVersionsNewestFirst(projectId).toList().map { build ->
            val gameBuildId = requireNotNull(build.id)
            val progress = documents.countIngestProgressByGameBuild(gameBuildId)
            val count = versions.countMapCasesByGameBuildId(gameBuildId)
            TestCaseGenerationCandidate(
                gameBuildId = gameBuildId.toString(),
                version = build.version,
                label = build.label,
                isEditor = build.isEditor,
                createdAt = build.createdAt,
                mapState = when {
                    progress == null || progress.received == 0L -> TestCaseGenerationCandidate.MapState.NO_MAP
                    progress.ingested == 0L -> TestCaseGenerationCandidate.MapState.NOT_READ
                    else -> TestCaseGenerationCandidate.MapState.READY
                },
                generated = (count?.mapCases ?: 0L) > 0L,
                mapCases = count?.mapCases?.toInt() ?: 0,
                judged = count?.judged?.toInt() ?: 0,
            )
        }
        return TestCaseGenerationCandidatesResponse(items)
    }

    private suspend fun generateFor(
        build: GameBuildEntity,
        carryOver: Boolean,
        overwrite: Boolean = false,
        verdicts: VerdictCarryOverService.Mode? = null,
        selected: Set<Long> = emptySet(),
    ): ContentMapTestCaseGenerationResponse {
        val gameBuildId = requireNotNull(build.id)
        val contentMapId = contentMaps.findByGameBuildId(gameBuildId)?.id
            ?.takeIf { documents.existsByContentMapIdAndIngestedAtIsNotNull(it) }
            ?: throw ConflictException("이 빌드에는 적재된 content map 이 없습니다.")
        val generated = generateInTransaction(
            build.projectId, contentMapId, build, carryOver, firstTimeOnly = !overwrite, verdicts = verdicts, selected = selected,
        )
        return ContentMapTestCaseGenerationResponse.of(generated.result, generated.carriedOver, generated.verdicts)
    }

    private fun alreadyGenerated() = ConflictException(
        "이 빌드는 이미 지도에서 test case 를 만들었습니다.",
        code = TEST_CASES_ALREADY_GENERATED,
    )

    /**
     * content map id 로 다시 생성한다. 멤버 확인을 하지 않는다 — 내부 포트에만 있는 경로다.
     * content map 이나 그 build 를 못 찾으면 예전과 같이 아무것도 하지 않고 0건을 돌려준다.
     */
    suspend fun regenerate(contentMapId: Long): MapTestCaseWriter.Result {
        val build = contentMaps.findById(contentMapId)?.let { gameBuilds.findById(it.gameBuildId) }
            ?: return MapTestCaseWriter.Result()
                .also { logger.warn("content map {} 의 build 를 찾지 못해 test case 를 생성하지 않는다", contentMapId) }
        return generateInTransaction(build.projectId, contentMapId, build).result
    }

    /**
     * 그 build 의 버전 몫을 만든다(ARTEL-1018). 앞 버전에서 판정이 난 케이스의 새 몫은 writer 가
     * `REGRESSION` 으로 시작시키고, 그 수가 결과의 `regression` 이다.
     *
     * [firstTimeOnly] 면 이미 지도 몫이 있는 build 를 막는다(ARTEL-1040). 사용자 경로는 늘 그렇고, internal 재생성만
     * 예전처럼 덮어쓴다.
     *
     * [carryOver] 면 이어서 바로 앞 버전에 있던 사용자 생성 케이스를 이 버전에 넣는다(ARTEL-1031).
     *
     * [verdicts] 를 주면 마지막으로 앞 버전 판정을 그 방식으로 이어받는다(ARTEL-1180). 사용자 생성 케이스까지 이 버전에
     * 들어온 뒤에 해야 그 몫도 이어받는다.
     */
    private suspend fun generateInTransaction(
        projectId: Long,
        contentMapId: Long,
        build: GameBuildEntity,
        carryOver: Boolean = true,
        firstTimeOnly: Boolean = false,
        verdicts: VerdictCarryOverService.Mode? = null,
        selected: Set<Long> = emptySet(),
    ): Generated {
        val written = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val gameBuildId = requireNotNull(build.id)
            // lock 을 잡은 뒤에 본다. 같은 build 를 둘이 동시에 만들면 뒤의 것이 여기서 막힌다.
            if (firstTimeOnly && versions.existsMapCasesByGameBuildId(gameBuildId)) throw alreadyGenerated()
            val result = writer.rewrite(projectId, gameBuildId, contentMapId)
            val carriedOver = if (carryOver) manualCases.carryOver(projectId, gameBuildId) else 0
            Generated(result, carriedOver, verdicts?.let { verdictCarry.afterGeneration(gameBuildId, it, selected) })
        }
        // rewrite 는 build 하나에서 여러 행을 쓰므로 행마다 알리지 않고 commit 뒤에 한 번만 알린다.
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES)
        return written
    }

    private data class Generated(
        val result: MapTestCaseWriter.Result,
        val carriedOver: Int,
        val verdicts: VerdictCarryOverService.Outcome?,
    )

    companion object {
        const val TEST_CASES_ALREADY_GENERATED = "TEST_CASES_ALREADY_GENERATED"
    }
}
