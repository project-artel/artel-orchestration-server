package kr.artel.orchestration.project.service

import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.dto.DownloadTicketResponse
import kr.artel.orchestration.project.dto.ProjectDocumentResponse
import kr.artel.orchestration.project.dto.RegisterDocumentRequest
import kr.artel.orchestration.project.dto.UploadTicketRequest
import kr.artel.orchestration.project.dto.UploadTicketResponse
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 기획서는 PDF만 받는다. 배포마다 다르게 둘 수 있으면 파서가 못 읽는 형식이 들어온다. */
const val PDF_CONTENT_TYPE = "application/pdf"

/** PDF 매직 넘버. 파일이 실제로 PDF인지 아는 유일한 방법이다. */
private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

/** 버전 충돌 재시도 횟수. 동시 업로드가 몰려도 몇 번이면 빈 버전을 찾는다. */
private const val VERSION_RETRY_ATTEMPTS = 5L

/**
 * 기획서 업로드·조회.
 *
 * 원본 바이트는 서버를 지나지 않는다. 클라이언트가 presigned URL로 S3에 직접 올리고,
 * 서버는 티켓을 발급하고 올라온 결과를 검증해 메타데이터만 기록한다.
 */
@Service
class ProjectDocumentService(
    private val projectRepository: ProjectRepository,
    private val documentRepository: ProjectDocumentRepository,
    private val documentAssembler: ProjectDocumentAssembler,
    private val storage: DocumentStorage,
    private val properties: StorageProperties,
    private val clock: Clock
) {
    /**
     * 업로드 티켓을 발급한다. 형식과 크기를 여기서 먼저 막아 규격 밖 파일이 S3에 닿지 않게 한다.
     *
     * Content-Type은 서명에 포함되므로, 다른 타입을 신고한 PUT은 S3가 직접 거부한다.
     */
    fun createUploadTicket(
        userId: Long,
        projectId: Long,
        request: UploadTicketRequest
    ): Mono<UploadTicketResponse> =
        requireAccessible(projectId, userId).map {
            validateUploadRequest(request)

            val objectKey = objectKeyFor(projectId, request.fileName)
            val presigned = storage.presignUpload(objectKey, PDF_CONTENT_TYPE, request.sizeBytes)

            UploadTicketResponse(
                uploadUrl = presigned.url,
                objectKey = objectKey,
                requiredHeaders = presigned.requiredHeaders,
                expiresAt = presigned.expiresAt
            )
        }

    /**
     * 올라온 객체를 기획서 한 버전으로 등록한다. 이 호출이 성공해야 문서가 존재한다.
     *
     * headObject가 돌려주는 Content-Type은 클라이언트가 신고한 값을 그대로 되돌려주는 것이라
     * 내용을 보장하지 않는다. 그래서 앞부분을 실제로 읽어 PDF 매직 넘버를 확인한다.
     */
    @Transactional
    fun register(
        userId: Long,
        projectId: Long,
        request: RegisterDocumentRequest
    ): Mono<ProjectDocumentResponse> =
        requireAccessible(projectId, userId)
            .flatMap { verifyUploadedObject(projectId, request.objectKey) }
            .flatMap { stored ->
                saveNextVersion(
                    projectId = projectId,
                    userId = userId,
                    objectKey = request.objectKey,
                    fileName = fileNameFrom(request.objectKey),
                    sizeBytes = stored.sizeBytes
                )
            }
            .flatMap(documentAssembler::toResponse)

    @Transactional(readOnly = true)
    fun list(userId: Long, projectId: Long): Mono<List<ProjectDocumentResponse>> =
        requireAccessible(projectId, userId)
            .flatMap {
                documentRepository.findByProjectIdOrderByVersionDesc(projectId).collectList()
            }
            .flatMap(documentAssembler::toResponses)

    /** 다운로드 URL은 요청할 때마다 새로 만든다. 오래 사는 링크를 DOM에 박아두지 않기 위해서다. */
    @Transactional(readOnly = true)
    fun createDownloadTicket(
        userId: Long,
        projectId: Long,
        documentId: Long
    ): Mono<DownloadTicketResponse> =
        requireAccessible(projectId, userId)
            .flatMap { documentRepository.findByIdAndProjectId(documentId, projectId) }
            .map { document ->
                val presigned = storage.presignDownload(document.objectKey, document.fileName)
                DownloadTicketResponse(presigned.url, presigned.expiresAt)
            }

    /** 접근할 수 없거나 삭제된 프로젝트는 빈 Mono다. 컨트롤러가 404로 옮긴다. */
    private fun requireAccessible(projectId: Long, userId: Long) =
        projectRepository.findAccessibleById(projectId, userId)

    private fun validateUploadRequest(request: UploadTicketRequest) {
        if (!request.fileName.lowercase().endsWith(".pdf")) {
            throw InvalidDocumentException("기획서는 PDF 파일만 올릴 수 있습니다.")
        }
        if (request.contentType != PDF_CONTENT_TYPE) {
            throw InvalidDocumentException("Content-Type은 $PDF_CONTENT_TYPE 이어야 합니다.")
        }
        if (request.sizeBytes > properties.maxUploadBytes) {
            throw InvalidDocumentException(
                "기획서는 ${properties.maxUploadBytes / 1024 / 1024}MB를 넘을 수 없습니다."
            )
        }
    }

    private fun verifyUploadedObject(projectId: Long, objectKey: String) =
        // 다른 프로젝트의 키를 등록해 남의 기획서를 가져오는 것을 막는다.
        if (!objectKey.startsWith(objectKeyPrefix(projectId))) {
            Mono.error(InvalidDocumentException("이 프로젝트의 업로드 키가 아닙니다."))
        } else {
            storage.head(objectKey)
                .switchIfEmpty(
                    Mono.error(InvalidDocumentException("업로드된 파일을 찾을 수 없습니다."))
                )
                .flatMap { stored ->
                    when {
                        stored.sizeBytes <= 0 ->
                            Mono.error(InvalidDocumentException("빈 파일은 올릴 수 없습니다."))

                        stored.sizeBytes > properties.maxUploadBytes ->
                            Mono.error(
                                InvalidDocumentException(
                                    "기획서는 ${properties.maxUploadBytes / 1024 / 1024}MB를 넘을 수 없습니다."
                                )
                            )

                        else -> requirePdfContent(objectKey).thenReturn(stored)
                    }
                }
        }

    private fun requirePdfContent(objectKey: String): Mono<Void> =
        storage.readPrefix(objectKey, PDF_MAGIC.size)
            .switchIfEmpty(Mono.error(InvalidDocumentException("업로드된 파일을 찾을 수 없습니다.")))
            .flatMap { prefix ->
                if (prefix.size < PDF_MAGIC.size || !prefix.copyOf(PDF_MAGIC.size).contentEquals(PDF_MAGIC)) {
                    Mono.error(InvalidDocumentException("PDF 파일이 아닙니다."))
                } else {
                    Mono.empty()
                }
            }

    /**
     * 다음 버전으로 저장한다.
     *
     * MAX(version) + 1은 읽고 쓰는 사이에 경합이 난다. 유니크 제약이 그 충돌을 예외로 만들고,
     * 여기서 다시 읽어 재시도한다. 재시도마다 최대 버전을 새로 조회해야 같은 값으로 계속
     * 부딪히지 않는다.
     */
    private fun saveNextVersion(
        projectId: Long,
        userId: Long,
        objectKey: String,
        fileName: String,
        sizeBytes: Long
    ): Mono<ProjectDocumentEntity> =
        Mono.defer {
            documentRepository.findMaxVersion(projectId).flatMap { maxVersion ->
                documentRepository.save(
                    ProjectDocumentEntity(
                        projectId = projectId,
                        version = maxVersion + 1,
                        objectKey = objectKey,
                        fileName = fileName,
                        contentType = PDF_CONTENT_TYPE,
                        sizeBytes = sizeBytes,
                        uploadedBy = userId,
                        uploadedAt = Instant.now(clock)
                    )
                )
            }
        }.retryWhen(
            Retry.max(VERSION_RETRY_ATTEMPTS)
                .filter { it is DataIntegrityViolationException }
        )

    /**
     * 키에 프로젝트 id와 무작위 값을 함께 넣는다. 프로젝트 접두사는 등록 시 소유 검증에 쓰이고,
     * 무작위 값은 같은 이름을 여러 번 올려도 이전 버전을 덮어쓰지 않게 한다.
     */
    private fun objectKeyFor(projectId: Long, fileName: String): String =
        "${objectKeyPrefix(projectId)}${UUID.randomUUID()}/${sanitize(fileName)}"

    private fun objectKeyPrefix(projectId: Long) = "projects/$projectId/documents/"

    private fun fileNameFrom(objectKey: String) = objectKey.substringAfterLast('/')

    /**
     * 경로 구분자와 특수 문자를 걷어내 키가 다른 경로로 새지 않게 한다.
     * isLetterOrDigit이 한글을 포함하므로 한국어 파일명은 그대로 살아남는다.
     */
    private fun sanitize(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.isLetterOrDigit() || it in "-_. ()[]" }.trim()
        return cleaned.ifBlank { "document.pdf" }.take(200)
    }
}

/** 업로드 규격을 벗어난 요청. 컨트롤러가 400으로 옮긴다. */
class InvalidDocumentException(message: String) : RuntimeException(message)
