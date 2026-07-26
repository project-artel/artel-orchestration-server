package kr.artel.orchestration.issue.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.issue.dto.IssueResponse
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

private const val MAX_ISSUE_DETAIL_BYTES = 1024 * 1024

/**
 * QA 이슈의 저장(인바운드)과 조회(엔드유저) 담당.
 *
 * 저장은 Agent 프레임을 라우터가 검증한 뒤 호출하는 내부 경로이고, 조회는 프로젝트 멤버만
 * 접근하는 엔드유저 경로다. 두 경로의 인가 기준이 다르므로 저장은 인가를 다시 보지 않고,
 * 조회는 qa_try 접근권한([QaTryRepository.findAccessibleById])으로 가드한다.
 */
@Service
class IssueService(
    private val issueRepository: IssueRepository,
    private val tryRepository: QaTryRepository,
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

    /**
     * 한 실행의 이슈 목록. 접근권한을 먼저 확인해, 접근 불가(비멤버/없음)면 빈 Mono를 반환한다
     * (컨트롤러가 404로 매핑) — 멤버지만 이슈가 없는 경우의 빈 목록(200 [])과 구분된다.
     */
    fun listByQaTry(qaTryId: Long, userId: Long, size: Int): Mono<List<IssueResponse>> =
        tryRepository.findAccessibleById(qaTryId, userId)
            .flatMap { issueRepository.findByQaTryId(qaTryId, size).map { it.toResponse() }.collectList() }

    private fun IssueEntity.toResponse(): IssueResponse =
        IssueResponse(
            id = requireNotNull(id).toString(),
            qaTryId = qaTryId.toString(),
            messageId = messageId,
            correlationId = correlationId,
            severity = severity,
            title = title,
            detail = objectMapper.readTree(detail.asString()),
            createdAt = requireNotNull(createdAt)
        )
}
