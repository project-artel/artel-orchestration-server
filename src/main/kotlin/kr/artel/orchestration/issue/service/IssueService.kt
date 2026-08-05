package kr.artel.orchestration.issue.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.issue.dto.IssuePageResponse
import kr.artel.orchestration.issue.dto.IssueResponse
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.entity.IssueStatus
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

private const val MAX_ISSUE_DETAIL_BYTES = 1024 * 1024

/** 한 번에 읽어갈 수 있는 이슈 수. `qa_log` 페이지와 같은 상한이다. */
const val MAX_PAGE_SIZE = 100

/**
 * QA 이슈의 저장·조회·해결 담당.
 *
 * 쓰기는 두 방향에서 온다. 하나는 Agent 프레임을 라우터([QaAgentInboundRouter])가 검증한 뒤
 * 부르는 [recordAgentIssue]이고, 다른 하나는 사람이 화면에서 누르는 [resolve]/[reopen]이다.
 * 앞쪽은 관측된 증거를 쌓고 뒤쪽은 그 증거를 처리했다는 사실만 남긴다 — 이슈 본문은 사람이
 * 고치지 않는다(ARTEL-245).
 *
 * 인가는 **여기서** 판정한다. 소비자마다 프로젝트 멤버십을 다시 구현하면 유출 지점이 그만큼
 * 늘어난다. 판정 경로는 두 가지이고 둘 다 기존 관례를 그대로 쓴다:
 * - 실행에 매달린 조회·전이 → [QaTryRepository.findAccessibleById]
 * - 프로젝트 스코프 목록 → [ProjectAccessService.isMember]
 *
 * 접근할 수 없는 것과 없는 것은 모두 [NotFoundException]이다. 남의 프로젝트에 이슈가 몇 건
 * 있는지를 403으로 알려주지 않는다.
 */
@Service
class IssueService(
    private val issueRepository: IssueRepository,
    private val qaTryRepository: QaTryRepository,
    private val projectAccess: ProjectAccessService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    /**
     * Agent가 보고한 이슈 한 건을 저장한다. messageId로 멱등하다 — 재전송된 프레임은
     * 중복 행을 만들지 않고 기존 행으로 흡수된다(QaLogService.append와 동일 패턴).
     *
     * [detail]에는 Agent payload 전체를 담되 1 MiB 상한을 둔다(qa_log와 동일).
     *
     * [reportedAt]은 Agent가 프레임에 찍은 이벤트 시각(envelope.timestamp)이다 — 우리가 저장하는
     * 수신 시각([createdAt])과 달리, 버그가 실제로 관측된 순간을 그대로 보존한다.
     */
    suspend fun recordAgentIssue(
        qaTryId: Long,
        messageId: String?,
        correlationId: String?,
        severity: String,
        title: String,
        reportedAt: Instant,
        payload: JsonNode
    ) {
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
            // Agent가 찍은 이벤트 시각(수신 시각과 구분해 그대로 보존).
            reportedAt = reportedAt,
            // 컬럼 기본값에 맡기지 않고 여기서 stamp한다: R2DBC가 insert 후 기본값 컬럼을
            // 다시 읽지 않아, 그러지 않으면 저장 결과의 createdAt이 null이다(QaLogService와 동일).
            createdAt = now,
            updatedAt = now
        )
        try {
            issueRepository.save(entity)
        } catch (error: DataIntegrityViolationException) {
            // messageId로 멱등 흡수: 재전송된 프레임이면 유니크 제약 위반을 기존 행으로 되돌린다.
            if (messageId == null) throw error
            issueRepository.findByQaTryIdAndMessageId(qaTryId, messageId) ?: throw error
        }
    }

    /**
     * 한 실행이 남긴 이슈 한 페이지. 실행이 없거나 참여하지 않는 프로젝트면 404.
     *
     * 필터가 없다. 한 실행의 이슈는 대개 몇 건이라 거를 것이 없고, 거르고 싶은 화면은 프로젝트
     * 목록을 쓴다.
     */
    suspend fun listByQaTry(
        qaTryId: Long,
        userId: Long,
        beforeId: Long?,
        size: Int
    ): IssuePageResponse {
        requireSize(size)
        qaTryRepository.findAccessibleById(qaTryId, userId) ?: throw NotFoundException()
        return page(issueRepository.findPageByQaTry(qaTryId, beforeId, size + 1).toList(), size)
    }

    /** 한 프로젝트의 이슈 한 페이지. 참여자가 아니면 404 — 빈 목록으로 위장하지 않는다. */
    suspend fun listByProject(
        projectId: Long,
        userId: Long,
        status: String?,
        severity: String?,
        beforeId: Long?,
        size: Int
    ): IssuePageResponse {
        requireSize(size)
        requireStatus(status)
        requireSeverity(severity)
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException()
        return page(
            issueRepository.findPageByProject(projectId, status, severity, beforeId, size + 1).toList(),
            size
        )
    }

    /**
     * 이슈를 해결로 표시한다. 이미 해결된 이슈면 아무것도 바꾸지 않고 성공한다.
     *
     * 멱등인 이유는 이 전이가 되돌릴 수 있기 때문이다. 토글을 두 번 누른 사용자에게 알릴 사건이
     * 아니다 — 되돌릴 수 없는 `qa_try` 취소가 409를 쓰는 것과 여기서 갈린다.
     */
    suspend fun resolve(issueId: Long, userId: Long) {
        requireAccessible(issueId, userId)
        val now = Instant.now(clock)
        issueRepository.resolve(issueId, resolvedAt = now, resolvedBy = userId, updatedAt = now)
    }

    /** 해결 표시를 되돌린다. 이미 미해결이면 그대로 성공한다([resolve]와 같은 이유). */
    suspend fun reopen(issueId: Long, userId: Long) {
        requireAccessible(issueId, userId)
        issueRepository.reopen(issueId, updatedAt = Instant.now(clock))
    }

    /**
     * 입력 검증은 컨트롤러가 아니라 여기서 한다. 목록 엔드포인트가 둘이고 앞으로 더 늘 수 있는데,
     * 각자 같은 검사를 두면 한쪽만 새 값을 놓친다. 상한이 서비스에 있어야 하는 이유는 하나 더
     * 있다 — [page]가 `size + 1`로 읽으므로 상한 없는 size는 `LIMIT` 오버플로가 된다.
     *
     * 값이 열거 밖이면 400이다. 빈 결과로 돌려주면 오타가 "이슈 없음"으로 보인다.
     */
    private fun requireSize(size: Int) {
        if (size !in 1..MAX_PAGE_SIZE) {
            throw BadRequestException("size must be between 1 and $MAX_PAGE_SIZE")
        }
    }

    private fun requireStatus(status: String?) {
        if (status != null && status !in IssueStatus.NAMES) {
            throw BadRequestException("status must be one of ${IssueStatus.NAMES}")
        }
    }

    private fun requireSeverity(severity: String?) {
        if (severity != null && severity !in IssueSeverity.NAMES) {
            throw BadRequestException("severity must be one of ${IssueSeverity.NAMES}")
        }
    }

    /**
     * 이슈가 존재하고 호출자가 그 실행에 접근할 수 있는지.
     *
     * 이슈 자체에는 프로젝트가 없다. 실행을 거쳐야 프로젝트에 닿으므로, 접근 판정은 실행 조회
     * 하나로 끝난다 — 조회 경로와 같은 규칙이다.
     */
    private suspend fun requireAccessible(issueId: Long, userId: Long) {
        val issue = issueRepository.findById(issueId) ?: throw NotFoundException()
        qaTryRepository.findAccessibleById(issue.qaTryId, userId) ?: throw NotFoundException()
    }

    /**
     * 한 칸 더 읽어온 목록을 페이지로 접는다.
     *
     * `size + 1`을 요청해두고 넘치면 잘라내는 방식이라, "다음이 있나"를 세는 별도 count 질의가
     * 필요 없다.
     */
    private fun page(rows: List<IssueEntity>, size: Int): IssuePageResponse {
        val hasMore = rows.size > size
        val items = if (hasMore) rows.take(size) else rows
        return IssuePageResponse(
            items = items.map { it.toResponse() },
            nextBeforeId = if (hasMore) items.lastOrNull()?.id?.toString() else null,
            hasMore = hasMore
        )
    }

    private fun IssueEntity.toResponse() = IssueResponse(
        id = requireNotNull(id).toString(),
        qaTryId = qaTryId.toString(),
        severity = severity,
        title = title,
        detail = objectMapper.readTree(detail.asString()),
        status = status,
        reportedAt = reportedAt,
        createdAt = createdAt,
        resolvedAt = resolvedAt,
        resolvedBy = resolvedBy?.toString()
    )
}
