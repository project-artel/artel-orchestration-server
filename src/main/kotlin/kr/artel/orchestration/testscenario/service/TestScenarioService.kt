package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestScenarioMessage
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioMessageRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * TestScenario 도메인 서비스. 컨트롤러가 얇게 유지되도록 생성/조회/중계/스트림의 비즈니스 로직을 담당한다.
 *
 * 세션 키(`userId:testScenarioId`) 조립, 엔티티 조립·JSON 직렬화, 저장/조회 로직은 여기서 처리하고,
 * Agent 프로토콜(WS)과 SSE Sink 관리는 각각 [TestScenarioAgentService]/[TestScenarioStreamManager]에 위임한다.
 */
@Service
class TestScenarioService(
    private val scenarioRepository: TestScenarioRepository,
    private val messageRepository: TestScenarioMessageRepository,
    private val agentService: TestScenarioAgentService,
    private val streamManager: TestScenarioStreamManager,
    private val objectMapper: ObjectMapper
) {

    /** 새 시나리오를 빈 payload로 생성하고 testScenarioId를 반환한다. */
    fun createScenario(projectId: Long): Mono<Long> =
        scenarioRepository.save(
            TestScenarioEntity(
                projectId = projectId,
                payload = Json.of(objectMapper.writeValueAsString(ScenarioDraft()))
            )
        ).map { it.id!! }

    /** 시나리오 단건 조회. 없으면 empty. */
    fun getScenario(testScenarioId: Long): Mono<ScenarioResponse> =
        scenarioRepository.findById(testScenarioId).map { entity ->
            ScenarioResponse(
                testScenarioId = entity.id!!,
                projectId = entity.projectId,
                payload = objectMapper.readValue(entity.payload.asString(), ScenarioDraft::class.java)
            )
        }

    /** 사용자별 프라이빗 채팅 스레드를 시간순으로 조회한다. */
    fun getMessages(testScenarioId: Long, appUserId: Long): Flux<MessageResponse> =
        messageRepository
            .findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(testScenarioId, appUserId)
            .map { MessageResponse(role = it.role, content = it.content, createdAt = it.createdAt) }

    /** FE가 Agent 응답을 실시간 수신하는 SSE 스트림. */
    fun stream(appUserId: Long, testScenarioId: Long): Flux<ServerSentEvent<ScenarioStreamEvent>> =
        streamManager.stream(sessionKey(appUserId, testScenarioId))

    /** 사용자 입력을 Agent로 중계한다. */
    fun relay(appUserId: Long, testScenarioId: Long, message: TestScenarioMessage): Mono<Void> =
        agentService.sendMessage(
            sessionKey(appUserId, testScenarioId),
            testScenarioId,
            appUserId,
            message.testScenarioMessage,
            message.draft
        )

    private fun sessionKey(appUserId: Long, testScenarioId: Long) = "$appUserId:$testScenarioId"
}
