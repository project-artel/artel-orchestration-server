package kr.artel.orchestration.project.controller

import kr.artel.orchestration.common.error.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.dto.DownloadTicketResponse
import kr.artel.orchestration.project.dto.ProjectDocumentResponse
import kr.artel.orchestration.project.dto.RegisterDocumentRequest
import kr.artel.orchestration.project.dto.UploadTicketRequest
import kr.artel.orchestration.project.dto.UploadTicketResponse
import kr.artel.orchestration.project.service.ProjectDocumentService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 기획서 업로드는 세 번의 호출로 이뤄진다(코루틴).
 *
 * 1. 업로드 URL 발급
 * 2. 클라이언트가 그 URL로 S3에 직접 PUT (서버를 지나지 않는다)
 * 3. 등록 — 이 호출이 성공해야 기획서가 존재한다
 *
 * 컨트롤러는 얇게: 상태코드 매핑(서비스가 null이면 404)만 하고, 비즈니스는 [ProjectDocumentService].
 */
@Tag(name = "Project Document", description = "프로젝트 기획서 업로드·조회")
@RestController
@RequestMapping("/api/projects/{projectId}/documents")
class ProjectDocumentController(
    private val documentService: ProjectDocumentService
) {
    @Operation(
        summary = "업로드 URL 발급",
        description = "PDF만 허용한다. 발급된 URL로 PUT할 때 세션 쿠키를 붙이면 서명이 깨진다."
    )
    @PostMapping("/upload-url")
    suspend fun createUploadTicket(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: UploadTicketRequest
    ): ResponseEntity<UploadTicketResponse> =
        documentService.createUploadTicket(appUserId, projectId, request)
            ?.let { ResponseEntity.ok(it) }
            ?: throw projectNotFound()

    @Operation(
        summary = "기획서 등록",
        description = "올라온 객체를 검증하고 새 버전으로 기록한다. 이전 버전은 남는다."
    )
    @PostMapping
    suspend fun register(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: RegisterDocumentRequest
    ): ResponseEntity<ProjectDocumentResponse> =
        documentService.register(appUserId, projectId, request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: throw projectNotFound()

    @Operation(summary = "기획서 이력", description = "최신 버전이 앞에 온다.")
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): ResponseEntity<List<ProjectDocumentResponse>> =
        documentService.list(appUserId, projectId)
            ?.let { ResponseEntity.ok(it) }
            ?: throw projectNotFound()

    @Operation(
        summary = "다운로드 URL 발급",
        description = "요청할 때마다 새로 만드는 단기 URL이다."
    )
    @GetMapping("/{documentId}/download-url")
    suspend fun createDownloadTicket(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "기획서 id", required = true) @PathVariable documentId: Long
    ): ResponseEntity<DownloadTicketResponse> =
        documentService.createDownloadTicket(appUserId, projectId, documentId)
            ?.let { ResponseEntity.ok(it) }
            ?: throw documentNotFound()

    private fun projectNotFound() =
        NotFoundException("프로젝트를 찾을 수 없습니다.")

    private fun documentNotFound() =
        NotFoundException("기획서를 찾을 수 없습니다.")
}
