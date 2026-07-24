package kr.artel.orchestration.project.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant

/**
 * 업로드 URL 발급 요청. 여기서 형식과 크기를 먼저 막아, 규격 밖 파일이 S3에 닿지 않게 한다.
 *
 * @property fileName 원본 파일명. `.pdf`여야 한다
 * @property contentType `application/pdf`여야 한다
 * @property sizeBytes 클라이언트가 신고한 크기. 등록 단계에서 실제 크기와 대조한다
 */
data class UploadTicketRequest(
    @field:NotBlank
    val fileName: String,

    @field:NotBlank
    val contentType: String,

    @field:Positive
    val sizeBytes: Long
)

/**
 * 발급된 업로드 티켓.
 *
 * @property uploadUrl 이 URL로 직접 PUT한다. 세션 쿠키를 붙이면 서명이 깨진다
 * @property objectKey 등록 요청에 그대로 돌려줘야 하는 값
 * @property requiredHeaders PUT에 반드시 그대로 실어야 하는 헤더. 서명에 포함되어 있다
 * @property expiresAt 이 시각 이후에는 URL이 거부된다
 */
data class UploadTicketResponse(
    val uploadUrl: String,
    val objectKey: String,
    val requiredHeaders: Map<String, String>,
    val expiresAt: Instant
)

/**
 * 업로드한 객체를 기획서 한 버전으로 등록하는 요청.
 *
 * @property objectKey 발급받은 티켓의 objectKey
 */
data class RegisterDocumentRequest(
    @field:NotBlank
    val objectKey: String
)

/**
 * 기획서 한 버전.
 *
 * @property parseStatus 추출 진행 상태. 업로드 직후 PENDING, game_context가 reference_context로
 *   적재되면 EXTRACTED
 */
data class ProjectDocumentResponse(
    val id: String,
    val version: Int,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedAt: Instant,
    val uploadedBy: DocumentUploaderResponse,
    val parseStatus: String
)

data class DocumentUploaderResponse(
    val id: String,
    val displayName: String
)

/** 매 요청마다 새로 발급하는 단기 다운로드 URL. */
data class DownloadTicketResponse(
    val downloadUrl: String,
    val expiresAt: Instant
)
