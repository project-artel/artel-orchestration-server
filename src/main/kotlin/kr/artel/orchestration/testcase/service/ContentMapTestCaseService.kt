package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kotlinx.coroutines.flow.toList
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
    private val versions: TestCaseVersionRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
) {

    private val logger = LoggerFactory.getLogger(ContentMapTestCaseService::class.java)

    /**
     * build 가 없거나 비멤버이거나 다른 프로젝트 build 면 404 다 — 셋을 구분하지 않는다
     * ([kr.artel.orchestration.contentmap.service.ContentMapViewService.read] 와 같은 확인).
     * build 에 content map 이 없거나 적재를 마친 문서가 없으면 409 다. editor build 도 409 다 — editor 는 게임 버전이
     * 아니어서 TC 를 따라가게 할 수 없다(ARTEL-1014). 그 지도는 게임 지식으로만 쓴다.
     *
     * 이미 지도에서 TC 를 만든 build 도 409(`TEST_CASES_ALREADY_GENERATED`)다(ARTEL-1040). 같은 build 의 새 TC 버전은
     * 아직 설계 중이라, 덮어쓰지 않고 막는다.
     *
     * [carryOver] 가 false 면 앞 버전의 사용자 생성 케이스를 이어 넣지 않고 `carriedOver` 는 0 이다.
     */
    suspend fun generate(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        carryOver: Boolean = true,
    ): ContentMapTestCaseGenerationResponse {
        val build = gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        return generateFor(build, carryOver)
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
                generated = versions.existsMapCasesByGameBuildId(gameBuildId),
            )
        }
        return TestCaseGenerationCandidatesResponse(items)
    }

    private suspend fun generateFor(build: GameBuildEntity, carryOver: Boolean): ContentMapTestCaseGenerationResponse {
        rejectEditor(build)
        val gameBuildId = requireNotNull(build.id)
        val contentMapId = contentMaps.findByGameBuildId(gameBuildId)?.id
            ?.takeIf { documents.existsByContentMapIdAndIngestedAtIsNotNull(it) }
            ?: throw ConflictException("이 빌드에는 적재된 content map 이 없습니다.")
        val (result, carriedOver) = generateInTransaction(build.projectId, contentMapId, build, carryOver, firstTimeOnly = true)
        return ContentMapTestCaseGenerationResponse.of(result, carriedOver)
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
    ): Pair<MapTestCaseWriter.Result, Int> =
        transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val gameBuildId = requireNotNull(build.id)
            // lock 을 잡은 뒤에 본다. 같은 build 를 둘이 동시에 만들면 뒤의 것이 여기서 막힌다.
            if (firstTimeOnly && versions.existsMapCasesByGameBuildId(gameBuildId)) throw alreadyGenerated()
            val result = writer.rewrite(projectId, gameBuildId, contentMapId)
            result to if (carryOver) manualCases.carryOver(projectId, gameBuildId) else 0
        }

    companion object {
        const val EDITOR_BUILD_HAS_NO_TEST_CASES = "EDITOR_BUILD_HAS_NO_TEST_CASES"
        const val TEST_CASES_ALREADY_GENERATED = "TEST_CASES_ALREADY_GENERATED"
    }
}
