package kr.artel.orchestration.contentmap.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.ContentMapResponse
import kr.artel.orchestration.contentmap.dto.StartContentMapScanResponse
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.scan.ContentMapScanService
import kr.artel.orchestration.contentmap.service.ContentMapViewService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
        @Parameter(description = "editor · editor-play · player. 생략하면 가장 최근에 알게 된 capture")
        @RequestParam(required = false) capture: String?,
    ): ContentMapResponse =
        view.read(appUserId, projectId, gameBuildId, parseCapture(capture))
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")

    /**
     * 어휘에 없는 값은 400 이다.
     *
     * 조용히 null 로 두면 오타(`?capture=editer`)가 "지도가 없다"로 보이고, 화면은 빈 상태를 그리며
     * 아무도 오타를 못 찾는다. 400 의 message 는 우리가 쓴 도메인 안내라 그대로 나가도 된다 —
     * 사용자가 보낸 값을 되쓰지 않는다.
     */
    private fun parseCapture(raw: String?): Capture? {
        if (raw == null) return null
        return Capture.from(raw)
            ?: throw BadRequestException(
                "capture 는 editor · editor-play · player 중 하나여야 합니다.",
                "invalid_capture",
            )
    }
}
