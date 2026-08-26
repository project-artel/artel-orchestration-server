package kr.artel.orchestration.contentmap.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketRequest
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketResponse
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentRequest
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentResponse
import kr.artel.orchestration.contentmap.dto.SceneCaptureRegistration
import kr.artel.orchestration.contentmap.dto.SceneCaptureTicketBatchRequest
import kr.artel.orchestration.contentmap.dto.SceneCaptureTicketBatchResponse
import kr.artel.orchestration.contentmap.dto.SceneCaptureUploadTicket
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapSceneCaptureEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.ContentMapSceneCaptureRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.storage.DocumentStorage
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID
import java.time.Instant

/**
 * 근거 문서를 받아 [ContentMapEntity] 한 행과 원본 포인터를 만든다. **적재는 하지 않는다** —
 * 씬·기능은 ARTEL-442 가 이 문서를 다시 읽어 만든다.
 *
 * 바이트가 이 서비스를 지나가지 않는다. 실측 문서가 1,413 KB 이고 WebFlux 기본 버퍼 상한은
 * 256 KB 인데, 전역 상한을 올리면 모든 엔드포인트가 함께 올라간다. 캡처와 기획서가 이미 쓰는
 * presign 을 그대로 쓴다.
 *
 * 헤더는 SDK 의 신고가 아니라 **업로드된 문서에서 직접 읽는다.** 신고하게 하면 그 값이 문서와
 * 어긋나도 서버가 알 수 없다.
 */
@Service
class EvidenceDocumentService(
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val documents: ContentMapDocumentRepository,
    private val sceneCaptures: ContentMapSceneCaptureRepository,
    private val scenes: SceneRepository,
    private val storage: DocumentStorage,
    private val properties: StorageProperties,
    private val objectMapper: ObjectMapper,
) {

    /**
     * 씬 캡처 업로드 티켓을 배치로 낸다. 접근할 수 없는 빌드면 null(→ 404).
     *
     * 키에 `gameBuildId` 를 박는 이유는 등록 때 그 접두사를 다시 확인하기 위해서다. 그렇게 하지
     * 않으면 남의 빌드에 올라간 객체 키를 자기 근거에 붙여 그 이미지를 자기 화면으로 끌어올 수 있다.
     */
    suspend fun createSceneCaptureTickets(
        userId: Long,
        gameBuildId: Long,
        request: SceneCaptureTicketBatchRequest,
    ): SceneCaptureTicketBatchResponse? {
        requireAccessibleBuild(gameBuildId, userId) ?: return null
        if (request.captures.map { it.sceneName }.toSet().size != request.captures.size) {
            throw BadRequestException("한 씬의 캡처는 한 장만 등록할 수 있습니다.", "duplicate_scene_capture")
        }

        val tickets = request.captures.map { capture ->
            if (capture.contentType != SCENE_CAPTURE_CONTENT_TYPE) {
                throw BadRequestException("씬 캡처는 JPEG만 올릴 수 있습니다.", "invalid_scene_capture_type")
            }
            if (capture.contentLength > properties.maxCaptureBytes) {
                throw BadRequestException("씬 캡처가 허용 크기를 넘습니다.", "scene_capture_too_large")
            }
            val objectKey = "$SCENE_CAPTURE_KEY_PREFIX/$gameBuildId/${UUID.randomUUID()}.jpg"
            val signed = storage.presignUpload(objectKey, capture.contentType, capture.contentLength)
            SceneCaptureUploadTicket(
                sceneName = capture.sceneName,
                objectKey = objectKey,
                uploadUrl = signed.url,
                requiredHeaders = signed.requiredHeaders,
                uploadExpiresAt = signed.expiresAt.toString(),
            )
        }
        return SceneCaptureTicketBatchResponse(tickets)
    }

    /**
     * 업로드 티켓을 발급한다. 접근할 수 없는 빌드면 null(→ 404).
     *
     * 부재와 권한 없음을 같은 404 로 묶는 것은, 구분해서 알려주면 id 를 훑어 남의 빌드가 존재한다는
     * 사실을 알아낼 수 있기 때문이다.
     */
    suspend fun createUploadTicket(
        userId: Long,
        gameBuildId: Long,
        request: EvidenceUploadTicketRequest,
    ): EvidenceUploadTicketResponse? {
        requireAccessibleBuild(gameBuildId, userId) ?: return null

        if (request.contentLength > properties.maxUploadBytes) {
            throw BadRequestException(
                "근거 문서는 ${properties.maxUploadBytes / 1024 / 1024}MB를 넘을 수 없습니다.",
                "evidence_document_too_large",
            )
        }

        val objectKey = "$EVIDENCE_KEY_PREFIX/$gameBuildId/${UUID.randomUUID()}.json"
        val presigned = storage.presignUpload(objectKey, CONTENT_TYPE, request.contentLength)

        return EvidenceUploadTicketResponse(
            objectKey = objectKey,
            uploadUrl = presigned.url,
            requiredHeaders = presigned.requiredHeaders,
            uploadExpiresAt = presigned.expiresAt.toString(),
        )
    }

    /**
     * 올라온 문서를 등록한다. 이 호출이 성공해야 문서가 존재한다.
     *
     * 순서가 있다. 헤더를 먼저 읽어 **모르는 세대를 여기서 막는다** — 지문과 해시를 계산해 행을
     * 만든 뒤에 거절하면 쓸 수 없는 지도가 남는다.
     */
    suspend fun register(
        userId: Long,
        gameBuildId: Long,
        request: RegisterEvidenceDocumentRequest,
    ): RegisterEvidenceDocumentResponse? {
        requireAccessibleBuild(gameBuildId, userId) ?: return null

        // 남의 빌드에 올린 키를 등록해 그 문서를 가져오는 것을 막는다.
        if (!request.objectKey.startsWith("$EVIDENCE_KEY_PREFIX/$gameBuildId/")) {
            throw BadRequestException("이 빌드의 업로드 키가 아닙니다.", "invalid_evidence_object_key")
        }

        val stored = storage.head(request.objectKey).awaitSingleOrNull()
            ?: throw NotFoundException("업로드된 근거 문서를 찾을 수 없습니다.")
        when {
            stored.sizeBytes <= 0 ->
                throw BadRequestException("빈 문서는 등록할 수 없습니다.", "invalid_evidence_document")

            // 티켓 단계의 contentLength 는 클라이언트가 신고한 값이고 서명에 들어가지도 않는다.
            // 실제로 올라온 크기는 여기서만 알 수 있다.
            stored.sizeBytes > properties.maxUploadBytes ->
                throw BadRequestException(
                    "근거 문서는 ${properties.maxUploadBytes / 1024 / 1024}MB를 넘을 수 없습니다.",
                    "evidence_document_too_large",
                )
        }

        val prefix = storage.readPrefix(request.objectKey, EvidenceDocumentHeaderReader.PREFIX_BYTES)
            .awaitSingleOrNull()
            ?: throw NotFoundException("업로드된 근거 문서를 찾을 수 없습니다.")
        val header = EvidenceDocumentHeaderReader.read(prefix, objectMapper)

        // 파일 크기와 무관하게 상수 메모리로 스트리밍한다.
        val contentHash = storage.sha256(request.objectKey).awaitSingleOrNull()
            ?: throw NotFoundException("업로드된 근거 문서를 찾을 수 없습니다.")

        // 중복 판정이 헤더 갱신보다 **먼저**다. 순서를 뒤집으면 옛 문서의 재전송이 지문을
        // 되돌린다 — A(aaaa) → B(bbbb) → A 재전송이면 저장은 건너뛰면서 지도의 지문만 aaaa 로
        // 돌아가고, "지문이 다르면 코드가 바뀐 것"이 거짓 발화해 검증이 되돌려진다.
        val existingMap = contentMaps.findByGameBuildIdAndCapture(gameBuildId, header.capture)
        if (existingMap != null) {
            documents.findByContentMapIdAndContentHash(existingMap.id!!, contentHash)?.let { existing ->
                replaceSceneCaptures(gameBuildId, existingMap.id, existing.id!!, request.sceneCaptures)
                // SDK 는 실행마다 새 키에 올리므로, 이미 아는 내용이면 방금 올라온 객체는 어떤
                // 행도 가리키지 않는다. 지우지 않으면 정상 경로에서 1.4MB 고아가 매번 쌓인다.
                if (existing.objectKey != request.objectKey) {
                    storage.delete(request.objectKey).awaitSingleOrNull()
                }
                return response(existingMap, existing, header, alreadyRegistered = true)
            }
        }

        val contentMap = upsertContentMap(gameBuildId, header, existingMap)

        // 같은 문서를 동시에 등록하면 유니크에 걸린다. 이 API 는 의미상 이미 멱등이므로
        // 500 이 아니라 "이미 등록됨"으로 답한다.
        val saved = try {
            documents.save(
                ContentMapDocumentEntity(
                    contentMapId = contentMap.id!!,
                    objectKey = request.objectKey,
                    contentHash = contentHash,
                    byteSize = stored.sizeBytes,
                )
            )
        } catch (e: DataIntegrityViolationException) {
            val winner = documents.findByContentMapIdAndContentHash(contentMap.id!!, contentHash)
                ?: throw e
            replaceSceneCaptures(gameBuildId, contentMap.id, winner.id!!, request.sceneCaptures)
            return response(contentMap, winner, header, alreadyRegistered = true)
        }
        replaceSceneCaptures(gameBuildId, contentMap.id!!, saved.id!!, request.sceneCaptures)
        return response(contentMap, saved, header, alreadyRegistered = false)
    }

    /**
     * 이 문서의 캡처를 통째로 갈아 끼운다.
     *
     * **덧붙이지 않고 지우고 다시 쓴다.** SDK 는 실행마다 등록하고, 그때마다 캡처에 성공한 씬 집합이
     * 달라질 수 있다. 덧붙이면 지난 실행에서 성공했던 낡은 이미지가 이번 실행의 실패 옆에 남아,
     * 화면이 실제로는 못 찍은 씬을 찍은 것처럼 보인다.
     */
    private suspend fun replaceSceneCaptures(
        gameBuildId: Long,
        contentMapId: Long,
        documentId: Long,
        captures: List<SceneCaptureRegistration>,
    ) {
        if (captures.map { it.sceneName }.toSet().size != captures.size) {
            throw BadRequestException("한 씬의 캡처는 한 장만 등록할 수 있습니다.", "duplicate_scene_capture")
        }

        val checked = captures.map { capture ->
            validateSceneCapture(gameBuildId, capture)
            ContentMapSceneCaptureEntity(
                documentId = documentId,
                sceneName = capture.sceneName,
                objectKey = capture.objectKey,
                contentType = capture.contentType,
                width = capture.width,
                height = capture.height,
                failureCode = capture.failureCode,
                capturedAt = Instant.now(),
            )
        }

        sceneCaptures.deleteByDocumentId(documentId)
        for (capture in checked) {
            val saved = sceneCaptures.save(capture)
            scenes.findByContentMapIdAndName(contentMapId, saved.sceneName)?.let { scene ->
                scenes.save(scene.copy(
                    imageObjectKey = saved.objectKey,
                    imageWidth = saved.width,
                    imageHeight = saved.height,
                    imageCapturedAt = saved.capturedAt,
                    imageFailureCode = saved.failureCode,
                ))
            }
        }
    }

    /**
     * 신고된 캡처가 실제로 올라온 것과 맞는지 본다.
     *
     * 신고 값만 믿으면 올린 적 없는 키가 행으로 앉는다. 그래서 [DocumentStorage.head] 로 객체의
     * 존재·크기·Content-Type 을 확인한다 — 저장된 Content-Type 은 클라이언트가 신고한 값이라
     * 내용을 보장하지 않지만, 최소한 다른 용도로 올린 객체를 이미지 자리에 끼우는 것은 막는다.
     */
    private suspend fun validateSceneCapture(gameBuildId: Long, capture: SceneCaptureRegistration) {
        if (capture.sceneName.isBlank() || capture.sceneName.length > 255) {
            throw BadRequestException("씬 이름이 올바르지 않습니다.", "invalid_scene_capture")
        }
        if (capture.objectKey == null) {
            if (capture.failureCode.isNullOrBlank() || capture.contentType != null ||
                capture.width != null || capture.height != null) {
                throw BadRequestException("실패한 씬 캡처의 메타데이터가 올바르지 않습니다.", "invalid_scene_capture")
            }
            return
        }
        if (!capture.objectKey.startsWith("$SCENE_CAPTURE_KEY_PREFIX/$gameBuildId/") ||
            capture.contentType != SCENE_CAPTURE_CONTENT_TYPE ||
            capture.width == null || capture.width <= 0 || capture.height == null || capture.height <= 0 ||
            capture.failureCode != null) {
            throw BadRequestException("씬 캡처의 메타데이터가 올바르지 않습니다.", "invalid_scene_capture")
        }
        val stored = storage.head(capture.objectKey).awaitSingleOrNull()
            ?: throw NotFoundException("업로드된 씬 캡처를 찾을 수 없습니다.")
        if (stored.sizeBytes <= 0 || stored.sizeBytes > properties.maxCaptureBytes ||
            stored.contentType != SCENE_CAPTURE_CONTENT_TYPE) {
            throw BadRequestException("업로드된 씬 캡처가 올바르지 않습니다.", "invalid_scene_capture")
        }
    }

    /**
     * (게임 빌드, capture) 당 한 행. 같은 조합으로 다시 오면 헤더를 갱신한다.
     *
     * capture 를 키에 넣는 이유: `editor` 는 저장된 값이고 `player` 는 플레이가 지나간 뒤의 값이라
     * 같은 필드가 다른 뜻이다. 한 행에 섞으면 그 구분이 사라진다.
     */
    private suspend fun upsertContentMap(
        gameBuildId: Long,
        header: EvidenceDocumentHeader,
        existing: ContentMapEntity?,
    ): ContentMapEntity {
        val next = (existing ?: ContentMapEntity(
            gameBuildId = gameBuildId,
            schemaVersion = header.schemaVersion,
            capture = header.capture,
            evidenceDigest = header.evidenceDigest,
        )).copy(
            schemaVersion = header.schemaVersion,
            evidencePromises = header.promises,
            evidenceDigest = header.evidenceDigest,
            unity = header.unity,
            platform = header.platform,
            backend = header.backend,
            development = header.development,
            sdkVersion = header.sdkVersion,
        )
        return try {
            contentMaps.save(next)
        } catch (e: DataIntegrityViolationException) {
            // 새 (빌드, capture) 를 동시에 만들면 uk_content_map_build_capture 에 걸린다.
            // 먼저 만든 쪽이 이긴 것이므로 그 행을 읽어 이어간다.
            contentMaps.findByGameBuildIdAndCapture(gameBuildId, header.capture) ?: throw e
        }
    }

    private fun response(
        contentMap: ContentMapEntity,
        document: ContentMapDocumentEntity,
        header: EvidenceDocumentHeader,
        alreadyRegistered: Boolean,
    ) = RegisterEvidenceDocumentResponse(
        contentMapId = contentMap.id!!,
        documentId = document.id!!,
        capture = header.capture,
        schemaVersion = header.schemaVersion,
        evidenceDigest = header.evidenceDigest,
        byteSize = document.byteSize,
        alreadyRegistered = alreadyRegistered,
    )

    /** 빌드가 없거나 그 프로젝트의 참여자가 아니면 null. 호출자가 404 로 바꾼다. */
    private suspend fun requireAccessibleBuild(gameBuildId: Long, userId: Long): Long? =
        gameBuilds.findAccessibleByIdForMember(gameBuildId, userId)?.id

    companion object {
        private const val EVIDENCE_KEY_PREFIX = "content-map-evidence"
        private const val CONTENT_TYPE = "application/json"
        private const val SCENE_CAPTURE_KEY_PREFIX = "content-map-scene-captures"
        private const val SCENE_CAPTURE_CONTENT_TYPE = "image/jpeg"
    }
}
