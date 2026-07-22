package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestScenarioMessage
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioMessageRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * TestScenario 도메인 서비스. 컨트롤러가 얇게 유지되도록 생성/조회/중계/스트림의 비즈니스 로직을 담당한다.
 *
 * 모든 작업은 **프로젝트 참여자(project_member)인지 검증**한다. 비참여자에게는 프로젝트/시나리오가
 * 존재하지 않는 것처럼(빈 결과 → 404) 보인다. 인증(JWT)만으로는 부족하고 해당 프로젝트 소속이어야 한다.
 *
 * 세션 키(`userId:testScenarioId`) 조립, 엔티티 조립·JSON 직렬화는 여기서 처리하고,
 * Agent 프로토콜(WS)과 SSE Sink 관리는 각각 [TestScenarioAgentService]/[TestScenarioStreamManager]에 위임한다.
 */
@Service
class TestScenarioService(
    private val scenarioRepository: TestScenarioRepository,
    private val messageRepository: TestScenarioMessageRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val agentService: TestScenarioAgentService,
    private val streamManager: TestScenarioStreamManager,
    private val objectMapper: ObjectMapper
) {

    /** 새 시나리오를 빈 payload로 생성하고 testScenarioId를 반환한다. 비참여자면 빈 Mono(→404). */
    fun createScenario(projectId: Long, appUserId: Long): Mono<Long> =
        isMember(projectId, appUserId)
            .filter { it }
            .flatMap {
                scenarioRepository.save(
                    TestScenarioEntity(
                        projectId = projectId,
                        payload = Json.of(objectMapper.writeValueAsString(ScenarioDraft()))
                    )
                ).map { it.id!! }
            }

    /** 시나리오 단건 조회. 없거나 비참여자면 빈 Mono(→404). */
    fun getScenario(testScenarioId: Long, appUserId: Long): Mono<ScenarioResponse> =
        accessibleScenario(testScenarioId, appUserId).map { entity ->
            ScenarioResponse(
                testScenarioId = entity.id!!,
                projectId = entity.projectId,
                payload = objectMapper.readValue(entity.payload.asString(), ScenarioDraft::class.java)
            )
        }

    /** 사용자별 프라이빗 채팅 스레드를 시간순으로 조회한다. 접근 불가면 빈 결과. */
    fun getMessages(testScenarioId: Long, appUserId: Long): Flux<MessageResponse> =
        accessibleScenario(testScenarioId, appUserId).flatMapMany {
            messageRepository
                .findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(testScenarioId, appUserId)
                .map { MessageResponse(role = it.role, content = it.content, createdAt = it.createdAt) }
        }

    /** FE가 Agent 응답을 실시간 수신하는 SSE 스트림. 접근 불가면 404. */
    fun stream(appUserId: Long, testScenarioId: Long): Flux<ServerSentEvent<ScenarioStreamEvent>> =
        accessibleScenario(testScenarioId, appUserId)
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMapMany { streamManager.stream(sessionKey(appUserId, testScenarioId)) }

    /** 사용자 입력을 Agent로 중계한다. 접근 불가면 404. */
    fun relay(appUserId: Long, testScenarioId: Long, message: TestScenarioMessage): Mono<Void> =
        accessibleScenario(testScenarioId, appUserId)
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap {
                agentService.sendMessage(
                    sessionKey(appUserId, testScenarioId),
                    testScenarioId,
                    appUserId,
                    message.testScenarioMessage,
                    message.draft
                )
            }

    /** 사용자가 해당 프로젝트 참여자인지. */
    private fun isMember(projectId: Long, appUserId: Long): Mono<Boolean> =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, appUserId).hasElement()

    /** 시나리오를 찾고 그 프로젝트 참여자인지 확인. 없거나 비참여자면 빈 Mono. */
    private fun accessibleScenario(testScenarioId: Long, appUserId: Long): Mono<TestScenarioEntity> =
        scenarioRepository.findById(testScenarioId)
            .filterWhen { isMember(it.projectId, appUserId) }

    private fun sessionKey(appUserId: Long, testScenarioId: Long) = "$appUserId:$testScenarioId"
}
