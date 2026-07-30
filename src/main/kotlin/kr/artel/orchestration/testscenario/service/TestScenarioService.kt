package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.common.error.NotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioListResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioSummary
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestScenarioMessage
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioMessageRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.executeAndAwait

/**
 * TestScenario 도메인 서비스(코루틴). 컨트롤러가 얇게 유지되도록 생성/조회/중계/스트림의 비즈니스 로직을 담당한다.
 *
 * 모든 작업은 **프로젝트 참여자(project_member)인지 검증**한다. 비참여자에게는 프로젝트/시나리오가
 * 존재하지 않는 것처럼(null → 404) 보인다. 인증(JWT)만으로는 부족하고 해당 프로젝트 소속이어야 한다.
 *
 * 세션 키(`userId:testScenarioId`) 조립, 엔티티 조립·JSON 직렬화는 여기서 처리하고,
 * Agent 프로토콜(WS)과 SSE Sink 관리는 각각 [TestScenarioAgentService]/[TestScenarioStreamManager]에 위임한다.
 */
@Service
class TestScenarioService(
    private val scenarioRepository: TestScenarioRepository,
    private val messageRepository: TestScenarioMessageRepository,
    private val accessService: TestScenarioAccessService,
    private val agentService: TestScenarioAgentService,
    private val streamManager: TestScenarioStreamManager,
    private val scenarioCaseRepository: kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository,
    private val runScenarioRepository: kr.artel.orchestration.testrun.repository.TestRunScenarioRepository,
    private val transactionalOperator: org.springframework.transaction.reactive.TransactionalOperator,
    private val objectMapper: ObjectMapper
) {

    /** 새 시나리오를 빈 payload로 생성하고 testScenarioId를 반환한다. 비참여자면 null(→404). */
    suspend fun createScenario(projectId: Long, appUserId: Long): Long? {
        if (!accessService.isMember(projectId, appUserId)) return null
        val saved = scenarioRepository.save(
            TestScenarioEntity(
                projectId = projectId,
                payload = Json.of(objectMapper.writeValueAsString(ScenarioDraft()))
            )
        )
        return saved.id!!
    }

    /** 시나리오 단건 조회. 없거나 비참여자면 null(→404). */
    suspend fun getScenario(testScenarioId: Long, appUserId: Long): ScenarioResponse? {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId) ?: return null
        return ScenarioResponse(
            testScenarioId = entity.id!!,
            projectId = entity.projectId,
            payload = objectMapper.readValue(entity.payload.asString(), ScenarioDraft::class.java)
        )
    }

    /** 사용자별 프라이빗 채팅 스레드를 시간순으로 조회한다. 접근 불가면 빈 목록. */
    suspend fun getMessages(testScenarioId: Long, appUserId: Long): List<MessageResponse> {
        accessService.accessibleScenario(testScenarioId, appUserId) ?: return emptyList()
        return messageRepository
            .findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(testScenarioId, appUserId)
            .map { MessageResponse(role = it.role, content = it.content, createdAt = it.createdAt) }
            .toList()
    }

    /**
     * 프로젝트에 속한 시나리오 목록을 조회한다. FE 목록 화면용 요약(제목/시간)만 담는다.
     * 비참여자면 404(존재하지 않는 것처럼 감춤).
     */
    suspend fun listScenarios(projectId: Long, appUserId: Long): ScenarioListResponse {
        if (!accessService.isMember(projectId, appUserId)) {
            throw NotFoundException()
        }
        val items = scenarioRepository.findByProjectId(projectId)
            .map { toSummary(it) }
            .toList()
        return ScenarioListResponse(items)
    }

    /** 프로젝트 스코프 시나리오 단건 조회. projectId 불일치·비참여자·미존재면 null(→404). */
    suspend fun getScenarioInProject(projectId: Long, testScenarioId: Long, appUserId: Long): ScenarioResponse? {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId) ?: return null
        if (entity.projectId != projectId) return null
        return ScenarioResponse(
            testScenarioId = entity.id!!,
            projectId = entity.projectId,
            payload = objectMapper.readValue(entity.payload.asString(), ScenarioDraft::class.java)
        )
    }

    /** 목록 응답용 요약 변환. 제목은 payload에서 추출한다. */
    private fun toSummary(entity: TestScenarioEntity): ScenarioSummary {
        val draft = objectMapper.readValue(entity.payload.asString(), ScenarioDraft::class.java)
        return ScenarioSummary(
            testScenarioId = entity.id!!,
            projectId = entity.projectId,
            title = draft.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    /**
     * canvas 편집을 실시간 저장한다(자동저장). Agent를 거치지 않은 순수 FE 편집을 payload에 덮어쓰고(last-write-wins),
     * 저장된 결과를 그대로 되돌려 FE가 로컬 draft와 DB 상태의 정합성을 맞추게 한다. 접근 불가면 404.
     */
    suspend fun testScenarioUpdate(appUserId: Long, testScenarioId: Long, draft: ScenarioDraft): ScenarioResponse {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        val saved = scenarioRepository.save(
            entity.copy(payload = Json.of(objectMapper.writeValueAsString(draft)))
        )
        return ScenarioResponse(
            testScenarioId = saved.id!!,
            projectId = saved.projectId,
            payload = objectMapper.readValue(saved.payload.asString(), ScenarioDraft::class.java)
        )
    }

    /** FE가 Agent 응답을 실시간 수신하는 SSE 스트림. 접근 불가면 404. */
    suspend fun stream(appUserId: Long, testScenarioId: Long): Flow<ServerSentEvent<ScenarioStreamEvent>> {
        accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        return streamManager.stream(sessionKey(appUserId, testScenarioId))
    }

    /**
     * 시나리오를 승인(확정)한다. 최종 draft가 있으면 payload로 저장하고 Agent 세션(WS)과 SSE를 닫는다.
     * 채팅 내역과 시나리오는 그대로 남는다(부산물 삭제 없음). 접근 불가면 404.
     */
    suspend fun testScenarioApprove(appUserId: Long, testScenarioId: Long, draft: ScenarioDraft?) {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        if (draft != null) {
            scenarioRepository.save(
                entity.copy(payload = Json.of(objectMapper.writeValueAsString(draft)))
            )
        }
        closeSession(appUserId, testScenarioId)
    }

    /**
     * 시나리오를 완전히 삭제한다. 접근 불가/미존재면 404.
     *
     * 정리 순서: Agent 세션·스트림을 닫고(진행 중 작업 중단), 조합 링크(케이스 조합·런 조합)를
     * 먼저 지운 뒤 시나리오 본체를 지운다. 채팅 메시지는 FK ON DELETE CASCADE로 함께 삭제된다.
     * 조합 정리와 본체 삭제는 한 트랜잭션으로 묶어 부분 삭제 상태가 남지 않게 한다.
     */
    suspend fun delete(appUserId: Long, testScenarioId: Long) {
        accessService.accessibleScenario(testScenarioId, appUserId) ?: throw NotFoundException()
        closeSession(appUserId, testScenarioId)
        transactionalOperator.executeAndAwait {
            scenarioCaseRepository.deleteByTestScenarioId(testScenarioId)
            runScenarioRepository.deleteByTestScenarioId(testScenarioId)
            scenarioRepository.deleteById(testScenarioId)
        }
    }

    /** Agent WS 세션과 SSE 스트림을 함께 닫는 정리 동작. */
    private fun closeSession(appUserId: Long, testScenarioId: Long) {
        val key = sessionKey(appUserId, testScenarioId)
        agentService.closeSession(key)
        streamManager.complete(key)
    }

    /** 사용자 입력을 Agent로 중계한다. 접근 불가면 404. */
    suspend fun relay(appUserId: Long, testScenarioId: Long, message: TestScenarioMessage) {
        val scenario = accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        agentService.sendMessage(
            sessionKey(appUserId, testScenarioId),
            testScenarioId,
            scenario.projectId,
            appUserId,
            message.testScenarioMessage,
            message.draft
        )
    }

    private fun sessionKey(appUserId: Long, testScenarioId: Long) = "$appUserId:$testScenarioId"
}
