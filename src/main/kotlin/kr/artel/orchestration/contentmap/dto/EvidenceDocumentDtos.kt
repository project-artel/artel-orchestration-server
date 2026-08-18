package kr.artel.orchestration.contentmap.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

/**
 * 업로드 티켓 요청. 바이트는 서버를 지나가지 않는다 — SDK 가 스토리지에 직접 올린다.
 *
 * 실측 문서가 1,413 KB 이고 WebFlux 기본 버퍼 상한은 256 KB 다. 전역 상한을 올리면 모든
 * 엔드포인트가 함께 올라가므로, 캡처(`QaCaptureService`)와 기획서가 이미 쓰는 presign 을 쓴다.
 */
data class EvidenceUploadTicketRequest(
    @field:Positive
    val contentLength: Long,
)

data class EvidenceUploadTicketResponse(
    val objectKey: String,
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val uploadExpiresAt: String,
)

/**
 * 등록 요청. **objectKey 하나만 받는다.**
 *
 * `schema`·`capture`·`build` 를 SDK 가 신고하게 하지 않는 것은, 그 값이 문서와 어긋나도 서버가
 * 알 수 없기 때문이다. 서버가 업로드된 문서의 앞부분을 직접 읽는다.
 */
data class RegisterEvidenceDocumentRequest(
    @field:NotBlank
    val objectKey: String,
)

/**
 * 등록 결과.
 *
 * [alreadyRegistered] 는 실패가 아니다 — SDK 는 게임 실행마다 등록하므로 같은 문서가 반복해서
 * 온다. 같은 내용이면 저장도 적재도 건너뛰고 기존 행을 그대로 돌려준다.
 */
data class RegisterEvidenceDocumentResponse(
    val contentMapId: Long,
    val documentId: Long,
    val capture: String,
    val schemaVersion: Int,
    val evidenceDigest: String,
    val byteSize: Long,
    val alreadyRegistered: Boolean,
)
