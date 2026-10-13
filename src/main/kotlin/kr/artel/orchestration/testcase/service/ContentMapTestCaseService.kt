package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.dto.ContentMapTestCaseGenerationResponse
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
 * 두 경로 모두 한 트랜잭션 안에서 프로젝트 folder lock → content map folder 찾기 또는 만들기 →
 * [MapTestCaseWriter.rewrite] 순서로 실행한다. 같은 build 를 두 요청이 동시에 생성해도 두 번째는 첫 번째가
 * commit 한 행을 보고 아무것도 새로 만들지 않는다.
 */
@Service
class ContentMapTestCaseService(
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val documents: ContentMapDocumentRepository,
    private val folderService: TestCaseFolderService,
    private val writer: MapTestCaseWriter,
    private val transactionalOperator: TransactionalOperator,
) {

    private val logger = LoggerFactory.getLogger(ContentMapTestCaseService::class.java)

    /**
     * build 가 없거나 비멤버이거나 다른 프로젝트 build 면 404 다 — 셋을 구분하지 않는다
     * ([kr.artel.orchestration.contentmap.service.ContentMapViewService.read] 와 같은 확인).
     * build 에 content map 이 없거나 적재를 마친 문서가 없으면 409 다.
     */
    suspend fun generate(userId: Long, projectId: Long, gameBuildId: Long): ContentMapTestCaseGenerationResponse {
        val build = gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("빌드를 찾을 수 없습니다.")
        val contentMapId = contentMaps.findByGameBuildId(gameBuildId)?.id
            ?.takeIf { documents.existsByContentMapIdAndIngestedAtIsNotNull(it) }
            ?: throw ConflictException("이 빌드에는 적재된 content map 이 없습니다.")
        return ContentMapTestCaseGenerationResponse.of(generateInTransaction(projectId, contentMapId, build))
    }

    /**
     * content map id 로 다시 생성한다. 멤버 확인을 하지 않는다 — 내부 포트에만 있는 경로다.
     * content map 이나 그 build 를 못 찾으면 예전과 같이 아무것도 하지 않고 0건을 돌려준다.
     */
    suspend fun regenerate(contentMapId: Long): MapTestCaseWriter.Result {
        val build = contentMaps.findById(contentMapId)?.let { gameBuilds.findById(it.gameBuildId) }
            ?: return MapTestCaseWriter.Result()
                .also { logger.warn("content map {} 의 build 를 찾지 못해 test case 를 생성하지 않는다", contentMapId) }
        return generateInTransaction(build.projectId, contentMapId, build)
    }

    private suspend fun generateInTransaction(
        projectId: Long,
        contentMapId: Long,
        build: GameBuildEntity,
    ): MapTestCaseWriter.Result =
        transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            val folder = folderService.findOrCreateContentMapFolder(projectId, contentMapId, buildNameOf(build))
            writer.rewrite(projectId, contentMapId, folder.id!!)
        }

    /** folder 이름에 쓰는 build 이름. 사람이 붙인 `label` 이 있으면 그것, 없으면 관찰한 `version`. */
    private fun buildNameOf(build: GameBuildEntity): String =
        build.label?.takeIf { it.isNotBlank() } ?: build.version
}
