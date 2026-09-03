package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.knowledge.agent.AgentExtractClient
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.project.dto.DocumentParseStatusResponse
import kr.artel.orchestration.project.dto.DocumentStreamEvent
import kr.artel.orchestration.project.entity.ParseStatus
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.service.DocumentEventStreamManager
import kr.artel.orchestration.project.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 업로드 문서를 Agent로 요약해 knowledge에 적재하는 코디네이터(docs pull 파이프라인).
 *
 * 흐름: parse_status=EXTRACTING → presignDownload(서명 GET URL) → Agent `/extract` →
 * knowledge 항목 배치를 [KnowledgeService.store](source=DOCS) → parse_status=EXTRACTED.
 * 어느 단계든 실패하면 parse_status=FAILED로 남긴다. 원본은 S3에 남아 재추출로 복구 가능.
 *
 * 요청 스레드를 막지 않도록 **백그라운드에서 실행**되는 것을 전제로 한다(업로드 확정 응답은 즉시,
 * 추출은 뒤따라 진행). 오류를 밖으로 던지지 않고 삼켜(로그+FAILED) 조용히 끝나게 한다.
 *
 * [markStatus]가 `parse_status`를 바꾸는 유일한 자리라, 여기서 [DocumentEventStreamManager]로
 * `document` SSE 프레임을 함께 publish한다(ARTEL-760). `parse_status`가 바뀌는 자리를 하나 더
 * 만들지 않고 이 자리에 얹는다.
 */
@Service
class DocumentKnowledgeExtractionService(
    private val storage: DocumentStorage,
    private val projectDocumentRepository: ProjectDocumentRepository,
    private val agentExtractClient: AgentExtractClient,
    private val knowledgeService: KnowledgeService,
    private val streamManager: DocumentEventStreamManager
) {
    private val logger = LoggerFactory.getLogger(DocumentKnowledgeExtractionService::class.java)

    /**
     * 이 서버 프로세스가 지금 추출을 들고 있는 `documentId` 집합이다(ARTEL-760).
     *
     * [isStale]의 유일한 근거다. 추출은 `backgroundScope`(`ProjectDocumentService`) 위의
     * fire-and-forget이라 서버가 재시작되면 진행 중이던 작업이 통째로 사라지고, DB의
     * `parse_status`는 `EXTRACTING`인 채로 굳는다. 새 프로세스에서는 이 집합이 빈 채로
     * 시작하므로, 그런 굳은 행은 곧바로 `isStale = true`로 잡힌다. `ConcurrentHashMap`
     * 기반 set이라 `backgroundScope`의 여러 코루틴이 동시에 드나들어도 안전하다.
     */
    private val inFlightDocumentIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    suspend fun extractAndStoreForDocument(document: ProjectDocumentEntity) {
        val documentId = document.id
            ?: throw IllegalArgumentException("추출하려는 문서에 id가 없습니다.")

        inFlightDocumentIds += documentId
        try {
            markStatus(document, documentId, ParseStatus.EXTRACTING)
            val downloadUrl = presignDownloadUrl(document)
            val response = agentExtractClient.extract(downloadUrl, document.fileName, documentId)
            knowledgeService.store(
                projectId = document.projectId,
                // 사람이 올린 문서에서 나온 지식은 언제나 운영 지식창고의 것이다(ARTEL-256).
                // 실험 arm이 문서를 올리는 경로는 없고, 있어도 그 문서는 프로젝트의 사실이지
                // 그 arm의 산물이 아니다.
                scope = KnowledgeScope.PRODUCTION,
                source = KnowledgeSource.DOCS,
                sourceId = documentId,
                // Orche가 업로드 확정 때 계산해 보존한 파일 hash를 재사용한다(Agent metadata 대신
                // authoritative 값). document.contentHash는 register 이후 항상 채워져 있다.
                contentHash = document.contentHash,
                items = response.gameContext,
                // 문서 node의 summary가 되는 값이다(ARTEL-748). DOCS 배치는 이 값이 없으면
                // KnowledgeService.store가 죽는다.
                documentFileName = document.fileName
            )
            markStatus(document, documentId, ParseStatus.EXTRACTED)
        } catch (error: Exception) {
            logger.error("knowledge 추출 실패 documentId={}: {}", documentId, error.message, error)
            markStatus(document, documentId, ParseStatus.FAILED)
        } finally {
            inFlightDocumentIds -= documentId
        }
    }

    /**
     * SSE `stale` 계산 그 자체(ARTEL-760). `parseStatus == EXTRACTING`인데 이 서버가 그 문서의
     * 추출을 [inFlightDocumentIds]에 들고 있지 않을 때만 `true`다.
     *
     * `artel.agent.extract.enabled`가 꺼져 있으면 문서는 애초에 `EXTRACTING`으로 전이되지
     * 않는다(`ProjectDocumentService.triggerExtractionInBackground`가 아예 launch하지 않는다).
     * 그래서 이 스위치가 꺼진 배포에서는 `parseStatus == EXTRACTING`인 행 자체가 나오지 않고,
     * 이 함수도 그 전제 때문에 자연히 `stale`을 `true`로 내지 않는다 — 별도 분기가 필요 없다.
     *
     * snapshot(구독 시점 DB 조회)과 이 클래스가 실시간으로 publish하는 `document` 프레임이
     * 같은 함수를 쓴다. 실시간 프레임에서는 [inFlightDocumentIds]에 이미 넣은 뒤 상태를
     * `EXTRACTING`으로 바꾸므로 언제나 `false`로 나가고, `stale = true`는 재시작 뒤 새
     * 구독자가 받는 snapshot에서만 나타난다.
     */
    fun isStale(documentId: Long, parseStatus: String): Boolean =
        parseStatus == ParseStatus.EXTRACTING.name && documentId !in inFlightDocumentIds

    /** presign은 로컬 서명 계산(네트워크 없음)이라 그대로 호출한다. */
    private fun presignDownloadUrl(document: ProjectDocumentEntity): String =
        storage.presignDownload(document.objectKey, document.fileName).url

    private suspend fun markStatus(document: ProjectDocumentEntity, documentId: Long, status: ParseStatus) {
        projectDocumentRepository.updateParseStatus(documentId, status.name)
        streamManager.emit(
            document.projectId,
            DocumentStreamEvent(
                type = "document",
                document = DocumentParseStatusResponse(
                    documentId = documentId.toString(),
                    parseStatus = status.name,
                    stale = isStale(documentId, status.name)
                )
            )
        )
    }
}
