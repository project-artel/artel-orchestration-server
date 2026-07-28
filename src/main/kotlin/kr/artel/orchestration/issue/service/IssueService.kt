package kr.artel.orchestration.issue.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

private const val MAX_ISSUE_DETAIL_BYTES = 1024 * 1024

/**
 * QA 이슈의 저장 담당.
 *
 * 이슈는 사용자가 조회하는 데이터가 아니라 Agent가 QA 실행 중 보고한 근거를 쌓아두는 내부
 * 도메인이다. 저장은 Agent 프레임을 라우터([QaAgentInboundRouter])가 검증한 뒤 호출하는 내부
 * 경로뿐이며, 읽기는 향후 Report 작성 플로우가 이 서비스를 직접 호출하는 형태로 붙는다(그때
 * 인가는 Report 소비자가 책임진다). 그래서 여기서는 저장만 노출한다.
 */
@Service
class IssueService(
    private val issueRepository: IssueRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    /**
     * Agent가 보고한 이슈 한 건을 저장한다. messageId로 멱등하다 — 재전송된 프레임은
     * 중복 행을 만들지 않고 기존 행으로 흡수된다(QaLogService.append와 동일 패턴).
     *
     * [detail]에는 Agent payload 전체를 담되 1 MiB 상한을 둔다(qa_log와 동일).
     */
    fun recordAgentIssue(
        qaTryId: Long,
        messageId: String?,
        correlationId: String?,
        severity: String,
        title: String,
        payload: JsonNode
    ): Mono<Void> {
        val serialized = objectMapper.writeValueAsString(payload)
        require(serialized.toByteArray(StandardCharsets.UTF_8).size <= MAX_ISSUE_DETAIL_BYTES) {
            "Issue detail exceeds 1 MiB"
        }
        val now = Instant.now(clock)
        val entity = IssueEntity(
            qaTryId = qaTryId,
            messageId = messageId,
            correlationId = correlationId,
            severity = severity,
            title = title,
            detail = Json.of(serialized),
            // 컬럼 기본값에 맡기지 않고 여기서 stamp한다: R2DBC가 insert 후 기본값 컬럼을
            // 다시 읽지 않아, 그러지 않으면 저장 결과의 createdAt이 null이다(QaLogService와 동일).
            createdAt = now,
            updatedAt = now
        )
        return issueRepository.save(entity)
            .onErrorResume(DataIntegrityViolationException::class.java) { error ->
                if (messageId == null) Mono.error(error)
                else issueRepository.findByQaTryIdAndMessageId(qaTryId, messageId)
                    .switchIfEmpty(Mono.error(error))
            }
            .then()
    }
}
