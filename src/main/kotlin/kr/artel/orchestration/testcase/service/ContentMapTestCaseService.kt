package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.contentmap.dto.ContentMapStreamEvent
import kr.artel.orchestration.contentmap.scan.ContentMapEventStreamManager
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
import kr.artel.orchestration.testcase.dto.TestCaseGenerationResponse
import kr.artel.orchestration.testcase.generator.GenerationPhase
import kr.artel.orchestration.testcase.generator.GenerationProgress
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

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
    private val versions: TestCaseVersionRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
    private val projectChangeStreamManager: ProjectChangeStreamManager,
    private val generations: TestCaseGenerationRegistry,
    private val streamManager: ContentMapEventStreamManager,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ContentMapTestCaseService::class.java)

    /**
     * build 가 없거나 비멤버이거나 다른 프로젝트 build 면 404 다 — 셋을 구분하지 않는다
     * ([kr.artel.orchestration.contentmap.service.ContentMapViewService.read] 와 같은 확인).
     * build 에 content map 이 없거나 적재를 마친 문서가 없으면 409 다. editor build 도 409 다 — editor 는 게임 버전이
     * 아니어서 TC 를 따라가게 할 수 없다(ARTEL-1014). 그 지도는 게임 지식으로만 쓴다.
     *
     * 이미 지도에서 TC 를 만든 build 도 409(`TEST_CASES_ALREADY_GENERATED`)다(ARTEL-1040). [overwrite] 면 막지 않고
     * 그 버전의 지도 TC 를 지금 지도로 덮어쓴다(ARTEL-1041) — 남는 TC 의 판정은 그대로이고, 지도에서 사라진 TC 는 그
     * 판정과 함께 빠지며, 사용자 생성 TC 는 건드리지 않는다. 화면이 경고를 보인 뒤에만 보낸다.
     *
     * [carryOver] 가 false 면 앞 버전의 사용자 생성 케이스를 이어 넣지 않고 `carriedOver` 는 0 이다.
     */
    suspend fun generate(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        carryOver: Boolean = true,
        overwrite: Boolean = false,
    ): ContentMapTestCaseGenerationResponse {
        val build = gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        return generateFor(build, carryOver, overwrite)
    }

    /**
     * 스캔이 끝난 뒤 그 build 의 TC 를 만든다(ARTEL-1040). 멤버 확인은 스캔을 시킬 때 이미 했다. 규칙은 [generate] 와
     * 같아서, 막히면 같은 409 를 던진다.
     */
    suspend fun generateAfterScan(gameBuildId: Long, carryOver: Boolean): ContentMapTestCaseGenerationResponse {
        val build = gameBuilds.findById(gameBuildId) ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        return generateFor(build, carryOver)
    }

    /**
     * 이 build 에 지금 TC 를 만들 수 있는지 미리 본다. 지도는 보지 않는다 — 스캔을 시키는 자리에서 쓰고, 지도는 그
     * 스캔이 만든다. editor 이거나 이미 만든 build 면 [generate] 와 같은 409 다.
     */
    suspend fun requireGeneratable(build: GameBuildEntity) {
        rejectEditor(build)
        if (versions.existsMapCasesByGameBuildId(requireNotNull(build.id))) throw alreadyGenerated()
    }

    /** 라이브러리 모달의 후보. 게임 빌드 최신 순이고, editor 는 없다. 비멤버면 404 다. */
    suspend fun candidates(userId: Long, projectId: Long): TestCaseGenerationCandidatesResponse {
        if (!projectAccessService.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val items = gameBuilds.findNonEditorNewestFirst(projectId).toList().map { build ->
            val gameBuildId = requireNotNull(build.id)
            val progress = documents.countIngestProgressByGameBuild(gameBuildId)
            val count = versions.countMapCasesByGameBuildId(gameBuildId)
            TestCaseGenerationCandidate(
                gameBuildId = gameBuildId.toString(),
                version = build.version,
                label = build.label,
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
    ): ContentMapTestCaseGenerationResponse {
        rejectEditor(build)
        val gameBuildId = requireNotNull(build.id)
        val contentMapId = contentMaps.findByGameBuildId(gameBuildId)?.id
            ?.takeIf { documents.existsByContentMapIdAndIngestedAtIsNotNull(it) }
            ?: throw ConflictException("이 빌드에는 적재된 content map 이 없습니다.")
        val (result, carriedOver) = tracked(gameBuildId) { progress ->
            generateInTransaction(build.projectId, contentMapId, build, carryOver, firstTimeOnly = !overwrite, progress = progress)
        }
        return ContentMapTestCaseGenerationResponse.of(result, carriedOver)
    }

    /**
     * 도는 생성을 끊는다(ARTEL-1187). 한 트랜잭션이라 아무것도 바뀌지 않는다. 접근할 수 없는 빌드면 404, 도는
     * 생성이 없으면 409(`TEST_CASE_GENERATION_NOT_RUNNING`).
     */
    suspend fun cancel(userId: Long, projectId: Long, gameBuildId: Long) {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        if (!generations.cancel(gameBuildId)) {
            throw ConflictException("이 빌드에 돌고 있는 TC 생성이 없습니다.", GENERATION_NOT_RUNNING)
        }
        logger.info("지도 TC 생성 취소 요청 [gameBuildId={}]", gameBuildId)
    }

    /**
     * 생성 하나를 빌드 자리에 묶어 돌린다(ARTEL-1187). 자리를 잡고(같은 빌드에 돌고 있으면 409), 단계 · 진행을 화면
     * 지도 SSE 로 흘리고, 끝 상태(성공 · 실패 · 취소)를 남긴다.
     *
     * 일은 자식 코루틴으로 돈다. 취소 API 가 이 요청 전체가 아니라 그 일만 끊어야 409 로 "취소했다" 를 답할 수 있다.
     * 끊기면 트랜잭션이 롤백된다.
     */
    private suspend fun <T> tracked(gameBuildId: Long, work: suspend (GenerationProgress) -> T): T = supervisorScope {
        val run = async(start = CoroutineStart.LAZY) { work(Reporter(gameBuildId)) }
        generations.start(gameBuildId, run, Instant.now(clock))?.let {
            run.cancel()
            throw ConflictException("이 빌드의 TC 생성이 이미 돌고 있습니다. 끝난 뒤에 다시 시도해 주세요.", GENERATION_IN_PROGRESS)
        }
        generations.find(gameBuildId)?.let { publish(gameBuildId, it) }
        val started = System.nanoTime()
        try {
            run.await().also {
                finish(gameBuildId, TestCaseGenerationState.SUCCEEDED)
                logger.info("지도 TC 생성 완료 [gameBuildId={}, {}ms]", gameBuildId, (System.nanoTime() - started) / 1_000_000)
            }
        } catch (cancelled: CancellationException) {
            if (generations.cancelRequested(gameBuildId) && currentCoroutineContext().isActive) {
                finish(gameBuildId, TestCaseGenerationState.CANCELLED)
                throw ConflictException("TC 생성을 취소했습니다. 아무것도 바뀌지 않았습니다.", GENERATION_CANCELLED)
            }
            // 요청 자체가 끊겼다(화면을 닫는 등). 트랜잭션은 롤백됐다.
            finish(gameBuildId, TestCaseGenerationState.FAILED, REQUEST_DROPPED)
            throw cancelled
        } catch (failure: Exception) {
            logger.error("지도 TC 생성 실패 [gameBuildId={}]", gameBuildId, failure)
            finish(gameBuildId, TestCaseGenerationState.FAILED, (failure as? ApiException)?.message ?: GENERIC_FAILURE)
            throw failure
        }
    }

    private fun finish(gameBuildId: Long, state: TestCaseGenerationState, error: String? = null) {
        generations.finish(gameBuildId, state, Instant.now(clock), error)?.let { publish(gameBuildId, it) }
    }

    private fun publish(gameBuildId: Long, status: TestCaseGenerationStatus) {
        streamManager.emit(
            gameBuildId,
            ContentMapStreamEvent(type = GENERATION_EVENT, testCaseGeneration = TestCaseGenerationResponse.of(status)),
        )
    }

    /**
     * 생성기 · writer 가 알리는 진행을 레지스트리와 SSE 로 옮긴다. 기능 행마다 알려 오므로 단계가 바뀔 때와 끝날
     * 때 말고는 [REPORT_INTERVAL_NANOS] 에 한 번만 흘린다 — 행 7,863개를 다 흘리면 화면이 그리는 것보다 빨리 쌓인다.
     */
    private inner class Reporter(private val gameBuildId: Long) : GenerationProgress {
        private var lastPhase: GenerationPhase? = null
        private var lastAt = 0L

        override fun report(phase: GenerationPhase, done: Int, total: Int) {
            val now = System.nanoTime()
            if (phase == lastPhase && done != total && now - lastAt < REPORT_INTERVAL_NANOS) return
            lastPhase = phase
            lastAt = now
            val known = total > 0
            generations.progress(gameBuildId, phase, done.takeIf { known }, total.takeIf { known }, Instant.now(clock))
                ?.let { publish(gameBuildId, it) }
        }
    }

    private fun rejectEditor(build: GameBuildEntity) {
        if (build.isEditor) {
            throw ConflictException(
                "editor 는 게임 버전이 아니라서 test case 를 만들지 않습니다. 게임 빌드에서 생성하세요.",
                code = EDITOR_BUILD_HAS_NO_TEST_CASES,
            )
        }
    }

    private fun alreadyGenerated() = ConflictException(
        "이 빌드는 이미 지도에서 test case 를 만들었습니다.",
        code = TEST_CASES_ALREADY_GENERATED,
    )

    /**
     * content map id 로 다시 생성한다. 멤버 확인을 하지 않는다 — 내부 포트에만 있는 경로다.
     * content map 이나 그 build 를 못 찾으면 예전과 같이 아무것도 하지 않고 0건을 돌려준다. editor build 도 그렇다.
     */
    suspend fun regenerate(contentMapId: Long): MapTestCaseWriter.Result {
        val build = contentMaps.findById(contentMapId)?.let { gameBuilds.findById(it.gameBuildId) }
            ?: return MapTestCaseWriter.Result()
                .also { logger.warn("content map {} 의 build 를 찾지 못해 test case 를 생성하지 않는다", contentMapId) }
        if (build.isEditor) {
            logger.info("content map {} 은 editor build 의 것이라 test case 를 생성하지 않는다", contentMapId)
            return MapTestCaseWriter.Result()
        }
        return generateInTransaction(build.projectId, contentMapId, build).first
    }

    /**
     * 그 build 의 버전 몫을 만든다(ARTEL-1018). 앞 버전에서 판정이 난 케이스의 새 몫은 writer 가
     * `REGRESSION` 으로 시작시키고, 그 수가 결과의 `regression` 이다.
     *
     * [firstTimeOnly] 면 이미 지도 몫이 있는 build 를 막는다(ARTEL-1040). 사용자 경로는 늘 그렇고, internal 재생성만
     * 예전처럼 덮어쓴다.
     *
     * [carryOver] 면 이어서 바로 앞 버전에 있던 사용자 생성 케이스를 이 버전에 넣는다(ARTEL-1031). 그 수가 두 번째
     * 값이다.
     */
    private suspend fun generateInTransaction(
        projectId: Long,
        contentMapId: Long,
        build: GameBuildEntity,
        carryOver: Boolean = true,
        firstTimeOnly: Boolean = false,
        progress: GenerationProgress = GenerationProgress.NONE,
    ): Pair<MapTestCaseWriter.Result, Int> {
        val written = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val gameBuildId = requireNotNull(build.id)
            // lock 을 잡은 뒤에 본다. 같은 build 를 둘이 동시에 만들면 뒤의 것이 여기서 막힌다.
            if (firstTimeOnly && versions.existsMapCasesByGameBuildId(gameBuildId)) throw alreadyGenerated()
            val result = writer.rewrite(projectId, gameBuildId, contentMapId, progress)
            if (carryOver) progress.report(GenerationPhase.CARRYING_OVER, 0, 0)
            result to if (carryOver) manualCases.carryOver(projectId, gameBuildId) else 0
        }
        // rewrite 는 build 하나에서 여러 행을 쓰므로 행마다 알리지 않고 commit 뒤에 한 번만 알린다.
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES)
        return written
    }

    companion object {
        const val EDITOR_BUILD_HAS_NO_TEST_CASES = "EDITOR_BUILD_HAS_NO_TEST_CASES"
        const val TEST_CASES_ALREADY_GENERATED = "TEST_CASES_ALREADY_GENERATED"

        /** 같은 빌드에 생성이 이미 돌고 있다(ARTEL-1187). */
        const val GENERATION_IN_PROGRESS = "TEST_CASE_GENERATION_IN_PROGRESS"

        /** 사용자가 취소했다. 아무것도 바뀌지 않았다. */
        const val GENERATION_CANCELLED = "TEST_CASE_GENERATION_CANCELLED"

        /** 취소할 생성이 없다. */
        const val GENERATION_NOT_RUNNING = "TEST_CASE_GENERATION_NOT_RUNNING"

        const val GENERATION_EVENT = "testCaseGeneration"

        private const val REPORT_INTERVAL_NANOS = 300_000_000L
        private const val GENERIC_FAILURE = "TC 를 만들지 못했습니다. 아무것도 바뀌지 않았습니다."
        private const val REQUEST_DROPPED = "요청이 끊겨 TC 생성을 멈췄습니다. 아무것도 바뀌지 않았습니다."
    }
}
