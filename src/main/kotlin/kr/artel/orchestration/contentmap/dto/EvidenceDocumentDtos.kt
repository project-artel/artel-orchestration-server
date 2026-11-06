package kr.artel.orchestration.contentmap.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.Valid
import jakarta.validation.constraints.Size

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
 * 씬 한 장의 업로드 티켓 요청. 근거 문서와 마찬가지로 바이트는 서버를 지나가지 않는다.
 */
data class SceneCaptureTicketRequest(
    @field:NotBlank val sceneName: String,
    @field:NotBlank val contentType: String,
    @field:Positive val contentLength: Long,
    @field:Positive val width: Int,
    @field:Positive val height: Int,
)

/**
 * 티켓을 씬 수만큼 한 번에 받는다. 씬마다 왕복하면 수백 씬짜리 walk 가 그만큼 느려진다.
 */
data class SceneCaptureTicketBatchRequest(
    @field:Size(max = 256)
    @field:Valid
    val captures: List<SceneCaptureTicketRequest>,
)

data class SceneCaptureUploadTicket(
    val sceneName: String,
    val objectKey: String,
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val uploadExpiresAt: String,
)

data class SceneCaptureTicketBatchResponse(val captures: List<SceneCaptureUploadTicket>)

/**
 * 근거 등록에 함께 오는 씬 캡처 결과.
 *
 * **성공과 실패가 한 타입에 산다.** 캡처는 실패할 수 있고(지원하지 않는 render pipeline, 카메라
 * 없음), 그 이유는 화면이 보여야 하는 사실이다. 성공이면 [objectKey]·[contentType]·[width]·[height]
 * 가 모두 차고 [failureCode] 가 비며, 실패면 정확히 그 반대다. 섞이면 400 이다 — 반쯤 찬 행을
 * 받아 두면 화면이 무엇을 믿을지 정할 수 없다.
 */
data class SceneCaptureRegistration(
    @field:NotBlank val sceneName: String,
    val objectKey: String? = null,
    val contentType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val failureCode: String? = null,
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
    @field:Size(max = 256)
    @field:Valid
    val sceneCaptures: List<SceneCaptureRegistration> = emptyList(),
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
