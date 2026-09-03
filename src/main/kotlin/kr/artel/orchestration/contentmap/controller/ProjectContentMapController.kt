package kr.artel.orchestration.contentmap.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.ContentMapResponse
import kr.artel.orchestration.contentmap.dto.ContentMapStreamEvent
import kr.artel.orchestration.contentmap.dto.StartContentMapScanResponse
import kr.artel.orchestration.contentmap.scan.ContentMapScanService
import kr.artel.orchestration.contentmap.service.ContentMapViewService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 브라우저가 씬 명세를 **읽고, 스캔을 시키는** 자리.
 *
 * **문서를 받는 쪽은 여기 없다.** 근거 문서는 게임이 스스로 만들어 `/api/sdk` 하위로 올린다 —
 * 그 경로는 `@Order(1)` 의 별도 체인이 `aud=artel-sdk` 만 받아 브라우저 토큰을 거절하고, 그래야
 * SDK 키가 콘솔 API 전체를 열지 않는다. 사람이 파일을 손으로 옮길 이유도 없다. 화면의 버튼은
 * 붙어 있는 인스턴스에 **스캔을 시키는** 것이지 파일을 올리는 것이 아니고, 이 컨트롤러의 POST 가
 * 바로 그 버튼이다.
 *
 * 경로가 `/projects/{projectId}/` 를 지나므로 그 값이 빌드의 프로젝트와 맞는지 확인한다 — 안 하면
 * 아무 프로젝트 id 나 넣어도 통과하고, 그 화면이 남의 프로젝트 빌드를 자기 것처럼 보여 준다.
 * 검사는 [ContentMapViewService.read] 안에 있다. 컨트롤러에 두면 다음 진입점이 빠뜨릴 수 있다.
 */
@Tag(name = "Content Map", description = "빌드에 앉은 씬 명세를 읽는다")
@RestController
@RequestMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/content-map")
class ProjectContentMapController(
    private val view: ContentMapViewService,
    private val scan: ContentMapScanService,
) {

    /**
     * 붙어 있는 게임에 **지금 스캔하라**고 시킨다. 화면의 버튼 하나가 이 자리다.
     *
     * 파일을 받지 않는다. 근거 문서는 게임이 스스로 만들어 `/api/sdk` 하위로 올리고, 이 호출은
     * 그것을 시작시키기만 한다.
     *
     * **202 이고, 끝났다는 뜻이 아니다.** 스캔은 씬을 걸어 다니는 일이라 즉시 끝나지 않는다.
     * 응답은 어느 인스턴스가 명령을 받았는지와 `REQUESTED` 를 돌려주고, 화면은 위 조회 API 의
     * `lastScan.state` 가 `SUCCEEDED` 나 `FAILED` 로 움직이는 것을 본다.
     *
     * 상태코드 셋이 서로 다른 말을 한다:
     * - **404** — 빌드가 없거나 경로의 `projectId` 가 그 빌드의 것과 다르다
     * - **409** — 빌드는 있는데 그것을 실행 중인 게임이 붙어 있지 않다
     * - **202** — 명령이 나갔다
     *
     * 409 를 404 로 뭉개지 않는 것이 요점이다. 뭉개면 화면은 "빌드가 없다"와 "게임이 안 켜져
     * 있다"를 구분하지 못해, 버튼을 비활성으로 두면서 그 이유를 말할 수 없다.
     */
    @Operation(
        summary = "원격 근거 스캔 요청",
        description = "이 빌드를 실행 중인 게임에 근거 스캔을 시킨다. 즉시 202 로 답하며, 완료는 " +
            "조회 API 의 `lastScan` 으로 확인한다. 붙어 있는 게임이 없으면 409.",
    )
    @PostMapping("/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun startScan(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
    ): StartContentMapScanResponse {
        val status = scan.startScan(appUserId, projectId, gameBuildId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
        return StartContentMapScanResponse(
            gameInstanceId = status.gameInstanceId,
            gameInstanceName = status.gameInstanceName,
            state = status.state,
            requestedAt = status.requestedAt,
        )
    }

    /**
     * 이 빌드의 씬 명세를 읽는다. **`content_map` 을 프로덕션에서 읽는 첫 경로다.**
     *
     * 등록도 적재도 안 된 빌드에서 404 가 아니라 `contentMap: null` 인 것이 요점이다 — 빌드는
     * 존재하고 접근도 된다. 없는 것은 아직 아무도 올리지 않은 문서이고, 그것은 오류가 아니라 화면이
     * "아직 스캔한 적이 없다"를 그려야 하는 정상 상태다. 404 는 **빌드가 없거나 경로의 projectId 가
     * 그 빌드의 것과 다를 때**뿐이다.
     *
     * `capture` 질의 인자가 없다. 빌드마다 지도가 하나라 고를 것이 없다(ARTEL-642). 값이 사라진
     * 것은 아니고 씬마다의 `capture` 로 내려갔다.
     */
    @Operation(
        summary = "씬 명세 조회",
        description = "이 빌드에 앉은 씬·기능·전이·gap 을 한 번에 읽는다. " +
            "`contentMap` 이 null 이면 등록된 문서가 없고, `contentMap.ingestedAt` 이 null 이면 " +
            "등록만 되고 아직 앉지 않았다.",
    )
    @GetMapping
    suspend fun read(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
    ): ContentMapResponse =
        view.read(appUserId, projectId, gameBuildId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")

    /**
     * 스캔 상태와 문서 적재 진행을 흘리는 SSE. **push 다** — 화면이 [read] 를 되풀이해 부르지 않아도
     * 된다.
     *
     * `event:` 이름은 페이로드의 `type` 과 같다: `snapshot`(구독 직후 정확히 한 번), `scan`
     * ([kr.artel.orchestration.contentmap.scan.ScanState] 가 바뀔 때), `ingest`(문서 하나가 앉거나
     * 실패할 때의 진행 두 수), `document`(같은 순간 그 문서 한 행).
     *
     * **cookie 인증이다.** 브라우저의 `EventSource` 는 header 를 실을 수 없어 `Authorization` 이
     * 오지 않는다 — `artel_access_token` 쿠키만 온다(ARTEL-762 계약).
     *
     * **`suspend fun` 인 것이 요점이다.** [ContentMapViewService.events] 안의 접근 검사가 이 함수의
     * 반환보다 먼저 끝나므로, 접근할 수 없는 프로젝트·빌드는 스트림이 열리기 전에 404 로 끝난다.
     *
     * **서버가 먼저 끊지 않는다.** 스캔이 끝나도 스트림은 살아 있다 — 화면 단위 구독이지 작업 단위가
     * 아니다. 끝은 client 가 `EventSource.close()` 하는 것뿐이다. cursor 도 replay 도 없다.
     */
    @Operation(
        summary = "스캔·적재 진행 SSE",
        description = "이 빌드의 스캔 상태와 문서 적재 진행을 push 로 흘린다. 구독 직후 `snapshot` " +
            "프레임이 한 번 오고, 그 뒤로 상태가 바뀔 때마다 `scan` · `ingest` · `document` 가 온다. " +
            "서버가 먼저 끊지 않는다.",
    )
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
    ): Flow<ServerSentEvent<ContentMapStreamEvent>> =
        view.events(appUserId, projectId, gameBuildId)
}
