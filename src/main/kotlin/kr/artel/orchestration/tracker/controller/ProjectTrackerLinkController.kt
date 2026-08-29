package kr.artel.orchestration.tracker.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.tracker.dto.TrackerLinkEnvelope
import kr.artel.orchestration.tracker.dto.TrackerLinkUpsertRequest
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.service.ProjectTrackerLinkService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 프로젝트의 이슈 tracker `link`.
 *
 * 경로에 `provider` 가 없다. GitHub 을 경로에 박으면 Jira 를 붙일 때 화면이 부르는 주소가 갈라진다 —
 * `provider` 는 본문의 값이고, 조회·해제는 쿼리 파라미터로 고른다(기본값 하나뿐이라 지금은 생략된다).
 */
@Tag(name = "Issue tracker", description = "프로젝트와 외부 이슈 트래커의 연결")
@RestController
@RequestMapping("/api/projects/{projectId}/tracker-link")
class ProjectTrackerLinkController(
    private val service: ProjectTrackerLinkService
) {
    @Operation(summary = "트래커 연결 조회", description = "연결이 없으면 link가 null이다. 참여자가 아니면 404.")
    @GetMapping
    suspend fun read(
        @PathVariable projectId: Long,
        @RequestParam(defaultValue = "GITHUB") provider: String,
        @CurrentUserId appUserId: Long
    ): TrackerLinkEnvelope =
        TrackerLinkEnvelope(service.read(projectId, appUserId, requireProvider(provider)))

    @Operation(summary = "트래커 연결 설정", description = "소유자만. 저장 전에 저장소 접근을 확인한다.")
    @PutMapping
    suspend fun upsert(
        @PathVariable projectId: Long,
        @Valid @RequestBody request: TrackerLinkUpsertRequest,
        @CurrentUserId appUserId: Long
    ): TrackerLinkEnvelope =
        TrackerLinkEnvelope(service.upsert(projectId, appUserId, request))

    @Operation(
        summary = "트래커 연결 해제",
        description = "소유자만. 이미 나간 외부 이슈와 그 링크는 그대로 둔다."
    )
    @DeleteMapping
    suspend fun delete(
        @PathVariable projectId: Long,
        @RequestParam(defaultValue = "GITHUB") provider: String,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.delete(projectId, appUserId, requireProvider(provider))
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
