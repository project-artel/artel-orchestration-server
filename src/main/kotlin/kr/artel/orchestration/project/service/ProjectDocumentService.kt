package kr.artel.orchestration.project.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.dto.DownloadTicketResponse
import kr.artel.orchestration.project.dto.ProjectDocumentResponse
import kr.artel.orchestration.project.dto.RegisterDocumentRequest
import kr.artel.orchestration.project.dto.UploadTicketRequest
import kr.artel.orchestration.project.dto.UploadTicketResponse
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.StoredObject
import kr.artel.orchestration.knowledge.service.DocumentKnowledgeExtractionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
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
 * 기획서 업로드·조회(코루틴).
 *
 * 원본 바이트는 서버를 지나지 않는다. 클라이언트가 presigned URL로 S3에 직접 올리고,
 * 서버는 티켓을 발급하고 올라온 결과를 검증해 메타데이터만 기록한다.
 *
 * 전환 과도기: `DocumentStorage`는 아직 Reactor 포트라 `awaitSingleOrNull()`로 브리지한다.
 */
@Service
class ProjectDocumentService(
    private val projectRepository: ProjectRepository,
    private val documentRepository: ProjectDocumentRepository,
    private val documentAssembler: ProjectDocumentAssembler,
    private val storage: DocumentStorage,
    private val properties: StorageProperties,
    private val clock: Clock,
    private val extractionService: DocumentKnowledgeExtractionService,
    @Value("\${artel.agent.extract.enabled:true}") private val extractionEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(ProjectDocumentService::class.java)

    /**
     * 추출 파이프라인을 요청과 분리해 백그라운드로 띄우는 스코프.
     *
     * SupervisorJob이라 한 추출 작업의 실패가 다른 작업이나 스코프 전체를 무너뜨리지 않는다.
     * 원래 Reactor의 boundedElastic 스케줄러에서 구독하던 것을 대체한다.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 업로드 티켓을 발급한다. 형식과 크기를 여기서 먼저 막아 규격 밖 파일이 S3에 닿지 않게 한다.
     *
     * Content-Type은 서명에 포함되므로, 다른 타입을 신고한 PUT은 S3가 직접 거부한다.
     * 접근할 수 없는 프로젝트면 null(→ 404).
     */
    suspend fun createUploadTicket(
        userId: Long,
        projectId: Long,
        request: UploadTicketRequest
    ): UploadTicketResponse? {
        requireAccessible(projectId, userId) ?: return null

        validateUploadRequest(request)

        val objectKey = objectKeyFor(projectId, request.fileName)
        val presigned = storage.presignUpload(objectKey, PDF_CONTENT_TYPE, request.sizeBytes)

        return UploadTicketResponse(
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
     *
     * 접근할 수 없는 프로젝트면 null(→ 404). 추출 트리거는 저장 뒤(응답 조립 후) 백그라운드로 띄운다.
     *
     * ⚠️ 광역 트랜잭션으로 감싸지 않는다. DB 쓰기는 [saveNextVersion]의 단일 insert 하나뿐(원자적)이고,
     * 그 insert는 (project_id, version) 유니크 충돌 시 **재시도**해야 하는데, 트랜잭션 안에서 유니크 위반이
     * 나면 Postgres가 트랜잭션을 abort시켜 재시도가 회복 불가(→ 동시 업로드 500)가 된다. 원본 Reactor의
     * `retryWhen`이 재구독마다 새로 시작하던 것을, 여기서는 트랜잭션 밖 재시도 루프로 대체한다.
     */
    suspend fun register(
        userId: Long,
        projectId: Long,
        request: RegisterDocumentRequest
    ): ProjectDocumentResponse? {
        requireAccessible(projectId, userId) ?: return null
        val stored = verifyUploadedObject(projectId, request.objectKey)
        // 원본을 스트리밍하며 SHA-256 계산 → 프로젝트 단위 중복이면 등록 거부(추출도 안 함).
        val contentHash = storage.sha256(request.objectKey).awaitSingleOrNull()
            ?: throw InvalidDocumentException("업로드된 파일을 찾을 수 없습니다.")
        val document = rejectDuplicateThenSave(
            projectId = projectId,
            userId = userId,
            objectKey = request.objectKey,
            contentHash = contentHash,
            sizeBytes = stored.sizeBytes
        )

        // 응답은 즉시 반환하고, 추출→적재는 뒤에서 진행한다(완료 여부는 parse_status로 노출).
        val response = documentAssembler.toResponse(document)
        triggerExtractionInBackground(document)
        return response
    }

    /**
     * 추출 파이프라인을 요청과 분리해 백그라운드로 띄운다(fire-and-forget).
     *
     * 업로드 확정 응답을 LLM 지연에 묶지 않기 위해 별도 스코프에서 launch한다. 실패는
     * 코디네이터가 parse_status=FAILED로 남기므로 여기선 방어적으로 로그만 남긴다.
     * (프로세스 재시작 중이면 진행 중 작업은 유실될 수 있어, 재추출 트리거는 후속 과제다.)
     */
    private fun triggerExtractionInBackground(document: ProjectDocumentEntity) {
        if (!extractionEnabled) return
        backgroundScope.launch {
            try {
                extractionService.extractAndStoreForDocument(document)
            } catch (error: Throwable) {
                logger.error("추출 파이프라인 기동 실패 documentId={}", document.id, error)
            }
        }
    }

    /** 접근할 수 없거나 삭제된 프로젝트면 null(→ 404). */
    suspend fun list(userId: Long, projectId: Long): List<ProjectDocumentResponse>? {
        requireAccessible(projectId, userId) ?: return null
        val documents = documentRepository.findByProjectIdOrderByVersionDesc(projectId)
            .toList()
        return documentAssembler.toResponses(documents)
    }

    /** 다운로드 URL은 요청할 때마다 새로 만든다. 오래 사는 링크를 DOM에 박아두지 않기 위해서다. */
    suspend fun createDownloadTicket(
        userId: Long,
        projectId: Long,
        documentId: Long
    ): DownloadTicketResponse? {
        requireAccessible(projectId, userId) ?: return null
        val document = documentRepository.findByIdAndProjectId(documentId, projectId)
            ?: return null
        val presigned = storage.presignDownload(document.objectKey, document.fileName)
        return DownloadTicketResponse(presigned.url, presigned.expiresAt)
    }

    /** 접근할 수 없거나 삭제된 프로젝트는 null이다. 호출부가 null→404로 옮긴다. */
    private suspend fun requireAccessible(projectId: Long, userId: Long): ProjectEntity? =
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

    private suspend fun verifyUploadedObject(projectId: Long, objectKey: String): StoredObject {
        // 다른 프로젝트의 키를 등록해 남의 기획서를 가져오는 것을 막는다.
        if (!objectKey.startsWith(objectKeyPrefix(projectId))) {
            throw InvalidDocumentException("이 프로젝트의 업로드 키가 아닙니다.")
        }
        val stored = storage.head(objectKey).awaitSingleOrNull()
            ?: throw InvalidDocumentException("업로드된 파일을 찾을 수 없습니다.")
        when {
            stored.sizeBytes <= 0 ->
                throw InvalidDocumentException("빈 파일은 올릴 수 없습니다.")

            stored.sizeBytes > properties.maxUploadBytes ->
                throw InvalidDocumentException(
                    "기획서는 ${properties.maxUploadBytes / 1024 / 1024}MB를 넘을 수 없습니다."
                )
        }
        requirePdfContent(objectKey)
        return stored
    }

    private suspend fun requirePdfContent(objectKey: String) {
        val prefix = storage.readPrefix(objectKey, PDF_MAGIC.size).awaitSingleOrNull()
            ?: throw InvalidDocumentException("업로드된 파일을 찾을 수 없습니다.")
        if (prefix.size < PDF_MAGIC.size || !prefix.copyOf(PDF_MAGIC.size).contentEquals(PDF_MAGIC)) {
            throw InvalidDocumentException("PDF 파일이 아닙니다.")
        }
    }

    /**
     * 프로젝트 단위 파일 중복이면 등록을 거부한다. 같은 hash가 이미 있으면 방금 올라온 S3 객체를
     * 지우고 409로 막아, 중복 파일에 대한 Agent 추출 요청이 아예 안 나가게 한다.
     * (동시 업로드 경합의 마지막 방어선은 DB 부분 유니크 uk_project_document_project_hash다.)
     */
    private suspend fun rejectDuplicateThenSave(
        projectId: Long,
        userId: Long,
        objectKey: String,
        contentHash: String,
        sizeBytes: Long
    ): ProjectDocumentEntity {
        val exists = documentRepository.existsByProjectIdAndContentHash(projectId, contentHash)
        if (exists) {
            storage.delete(objectKey).awaitSingleOrNull()
            throw DuplicateDocumentException("이미 업로드된 파일입니다.")
        }
        return saveNextVersion(projectId, userId, objectKey, fileNameFrom(objectKey), sizeBytes, contentHash)
    }

    /**
     * 다음 버전으로 저장한다.
     *
     * MAX(version) + 1은 읽고 쓰는 사이에 경합이 난다. 유니크 제약이 그 충돌을 예외로 만들고,
     * 여기서 다시 읽어 재시도한다. 재시도마다 최대 버전을 새로 조회해야 같은 값으로 계속
     * 부딪히지 않는다.
     */
    private suspend fun saveNextVersion(
        projectId: Long,
        userId: Long,
        objectKey: String,
        fileName: String,
        sizeBytes: Long,
        contentHash: String
    ): ProjectDocumentEntity {
        var remaining = VERSION_RETRY_ATTEMPTS
        while (true) {
            try {
                val maxVersion = documentRepository.findMaxVersion(projectId)
                return documentRepository.save(
                    ProjectDocumentEntity(
                        projectId = projectId,
                        version = maxVersion + 1,
                        objectKey = objectKey,
                        fileName = fileName,
                        contentType = PDF_CONTENT_TYPE,
                        sizeBytes = sizeBytes,
                        uploadedBy = userId,
                        uploadedAt = Instant.now(clock),
                        contentHash = contentHash
                    )
                )
            } catch (e: DataIntegrityViolationException) {
                if (remaining-- <= 0) throw e
            }
        }
    }

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

/** 같은 프로젝트에 동일 파일(hash)이 이미 있어 업로드를 거부할 때. 409 Conflict로 매핑된다. */
class DuplicateDocumentException(message: String) : RuntimeException(message)
