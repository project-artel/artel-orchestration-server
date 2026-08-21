package kr.artel.orchestration.contentmap.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketRequest
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketResponse
import kr.artel.orchestration.contentmap.dto.IngestContentMapResponse
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentRequest
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentResponse
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.service.EvidenceDocumentService
import kr.artel.orchestration.game.repository.GameBuildRepository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사람이 브라우저에서 근거 문서를 올려 **행이 앉는 데까지** 가는 지점.
 *
 * [SdkContentMapController] 와 같은 서비스를 쓰는 거울이다. 둘로 나뉜 이유는 인증 체인이 경로
 * 접두사로 갈리기 때문이다 — `/api/sdk` 하위는 `@Order(1)` 의 별도 체인이 `aud=artel-sdk` 만 받아
 * 브라우저 토큰을 거절한다. 한 컨트롤러가 두 청중을 받으려면 그 분리를 약화시켜야 한다.
 *
 * 실제로 다른 것은 **경로와 projectId 검사뿐**이다. 경로가 `/projects/{projectId}/` 를 지나므로 그
 * 값이 빌드의 프로젝트와 맞는지 확인한다 — 안 하면 아무 프로젝트 id 나 넣어도 통과하고, 그 화면이
 * 남의 프로젝트 빌드를 자기 것처럼 보여 준다.
 *
 * ```
 * 1) POST .../content-map/ticket   업로드 URL 을 받는다
 * 2) 스토리지에 PUT                 (바이트는 이 서버를 지나가지 않는다)
 * 3) POST .../content-map          objectKey 를 등록한다
 * 4) POST .../content-map/ingest   씬·기능 행으로 앉힌다
 * ```
 *
 * 3 과 4 를 묶지 않는다. 나누면 "올라갔는데 적재가 실패했다"를 사람이 구분할 수 있고, 묶으면 그
 * 구분이 사라진다.
 */
@Tag(name = "Content Map", description = "근거 문서를 올려 씬 명세로 앉힌다")
@RestController
@RequestMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/content-map")
class ProjectContentMapController(
    private val evidenceDocuments: EvidenceDocumentService,
    private val ingest: ContentMapIngestService,
    private val gameBuilds: GameBuildRepository,
) {

    @Operation(
        summary = "근거 문서 업로드 티켓",
        description = "스토리지에 직접 올릴 단기 URL 을 발급한다. 바이트는 서버를 지나가지 않는다.",
    )
    @PostMapping("/ticket")
    suspend fun createTicket(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
        @Valid @RequestBody request: EvidenceUploadTicketRequest,
    ): EvidenceUploadTicketResponse =
        evidenceDocuments.createUploadTicket(appUserId, gameBuildId, request, projectId)
            ?: throw buildNotFound()

    @Operation(
        summary = "근거 문서 등록",
        description = "올라온 문서의 헤더를 서버가 직접 읽어 content_map 을 만들거나 갱신한다. " +
            "같은 내용이 다시 오면 기존 등록을 그대로 돌려준다. **적재는 하지 않는다.**",
    )
    @PostMapping
    suspend fun register(
        @CurrentUserId appUserId: Long,
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @Valid @RequestBody request: RegisterEvidenceDocumentRequest,
    ): RegisterEvidenceDocumentResponse =
        evidenceDocuments.register(appUserId, gameBuildId, request, projectId)
            ?: throw buildNotFound()

    /**
     * 이 빌드의 대기 문서를 적재한다.
     *
     * 한 문서가 깨져도 200 이다 — 요청은 정상 처리됐고 결과가 실패인 것이라, 같이 앉은 나머지 결과를
     * 버리지 않는다. 무엇이 왜 안 앉았는지는 `failed` 에 담겨 나가고 문서 행에도 남는다.
     *
     * 대기 문서가 없으면 두 배열 다 비어 나간다. "올릴 것이 없다"는 오류가 아니다.
     */
    @Operation(
        summary = "근거 문서 적재",
        description = "대기 중인 이 빌드의 문서를 씬·기능 행으로 앉힌다. 실패는 응답의 failed 에 담긴다.",
    )
    @PostMapping("/ingest")
    suspend fun ingest(
        @CurrentUserId appUserId: Long,
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
    ): IngestContentMapResponse {
        gameBuilds.findAccessibleById(gameBuildId, projectId, appUserId) ?: throw buildNotFound()
        return IngestContentMapResponse.of(ingest.ingestBuild(gameBuildId))
    }

    private fun buildNotFound() = NotFoundException("게임 빌드를 찾을 수 없습니다.")
}
