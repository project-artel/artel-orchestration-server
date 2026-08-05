package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.knowledge.agent.AgentExtractClient
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.project.entity.ParseStatus
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 업로드 문서를 Agent로 요약해 knowledge에 적재하는 코디네이터(docs pull 파이프라인).
 *
 * 흐름: parse_status=EXTRACTING → presignDownload(서명 GET URL) → Agent `/extract` →
 * knowledge 항목 배치를 [KnowledgeService.store](source=DOCS) → parse_status=EXTRACTED.
 * 어느 단계든 실패하면 parse_status=FAILED로 남긴다. 원본은 S3에 남아 재추출로 복구 가능.
 *
 * 요청 스레드를 막지 않도록 **백그라운드에서 실행**되는 것을 전제로 한다(업로드 확정 응답은 즉시,
 * 추출은 뒤따라 진행). 오류를 밖으로 던지지 않고 삼켜(로그+FAILED) 조용히 끝나게 한다.
 */
@Service
class DocumentKnowledgeExtractionService(
    private val storage: DocumentStorage,
    private val projectDocumentRepository: ProjectDocumentRepository,
    private val agentExtractClient: AgentExtractClient,
    private val knowledgeService: KnowledgeService
) {
    private val logger = LoggerFactory.getLogger(DocumentKnowledgeExtractionService::class.java)

    suspend fun extractAndStoreForDocument(document: ProjectDocumentEntity) {
        val documentId = document.id
            ?: throw IllegalArgumentException("추출하려는 문서에 id가 없습니다.")

        try {
            markStatus(documentId, ParseStatus.EXTRACTING)
            val downloadUrl = presignDownloadUrl(document)
            val response = agentExtractClient.extract(downloadUrl, document.fileName, documentId)
            knowledgeService.store(
                projectId = document.projectId,
                source = KnowledgeSource.DOCS,
                sourceId = documentId,
                // Orche가 업로드 확정 때 계산해 보존한 파일 hash를 재사용한다(Agent metadata 대신
                // authoritative 값). document.contentHash는 register 이후 항상 채워져 있다.
                contentHash = document.contentHash,
                items = response.gameContext
            )
            markStatus(documentId, ParseStatus.EXTRACTED)
        } catch (error: Exception) {
            logger.error("knowledge 추출 실패 documentId={}: {}", documentId, error.message, error)
            markStatus(documentId, ParseStatus.FAILED)
        }
    }

    /** presign은 로컬 서명 계산(네트워크 없음)이라 그대로 호출한다. */
    private fun presignDownloadUrl(document: ProjectDocumentEntity): String =
        storage.presignDownload(document.objectKey, document.fileName).url

    private suspend fun markStatus(documentId: Long, status: ParseStatus) {
        projectDocumentRepository.updateParseStatus(documentId, status.name)
    }
}
