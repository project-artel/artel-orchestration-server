package kr.artel.orchestration.referencecontext.service

import kr.artel.orchestration.project.entity.ParseStatus
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.referencecontext.agent.AgentExtractClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

/**
 * 업로드된 문서를 Agent로 요약해 reference_context에 적재하는 코디네이터(pull 파이프라인).
 *
 * 흐름: parse_status=EXTRACTING → presignDownload(서명 GET URL) → Agent /extract →
 * game_context를 타입별로 저장([ReferenceContextService.replaceDocumentReferenceContext]에서
 * 저장 후 parse_status=EXTRACTED). 어느 단계든 실패하면 parse_status=FAILED로 남긴다.
 *
 * 이 서비스는 요청 스레드를 막지 않도록 **백그라운드에서 구독**되는 것을 전제로 한다
 * (업로드 확정 응답은 즉시 반환되고, 추출은 뒤따라 진행된다). 원본은 S3에 남으므로
 * 실패해도 재추출로 복구할 수 있다.
 */
@Service
class ReferenceContextExtractionService(
    private val storage: DocumentStorage,
    private val projectDocumentRepository: ProjectDocumentRepository,
    private val agentExtractClient: AgentExtractClient,
    private val referenceContextService: ReferenceContextService
) {
    private val logger = LoggerFactory.getLogger(ReferenceContextExtractionService::class.java)

    /**
     * 문서 하나를 추출→적재한다. 성공 시 EXTRACTED(적재 서비스가 설정), 실패 시 FAILED.
     * 오류를 밖으로 던지지 않고 삼켜(로그+FAILED) 백그라운드 구독이 조용히 끝나게 한다.
     */
    fun extractAndStoreForDocument(document: ProjectDocumentEntity): Mono<Void> {
        val documentId = document.id
            ?: return Mono.error(IllegalArgumentException("추출하려는 문서에 id가 없습니다."))

        return markStatus(documentId, ParseStatus.EXTRACTING)
            .then(presignDownloadUrl(document))
            .flatMap { downloadUrl -> agentExtractClient.extractGameContext(downloadUrl, document.fileName) }
            .flatMap { response ->
                referenceContextService.replaceDocumentReferenceContext(
                    projectId = document.projectId,
                    sourceDocumentId = documentId,
                    gameContext = response.gameContext
                )
            }
            .onErrorResume { error ->
                logger.error("reference_context 추출 실패 documentId={}: {}", documentId, error.message, error)
                markStatus(documentId, ParseStatus.FAILED)
            }
    }

    /** presign은 로컬 서명 계산(네트워크 없음)이라 fromCallable로 감싸 리액티브 체인에 넣는다. */
    private fun presignDownloadUrl(document: ProjectDocumentEntity): Mono<String> =
        Mono.fromCallable { storage.presignDownload(document.objectKey, document.fileName).url }

    private fun markStatus(documentId: Long, status: ParseStatus): Mono<Void> =
        projectDocumentRepository.updateParseStatus(documentId, status.name).then()
}
