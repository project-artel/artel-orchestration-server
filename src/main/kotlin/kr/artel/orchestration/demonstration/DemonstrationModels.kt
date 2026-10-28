package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.common.error.BadRequestException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.UUID

@ConfigurationProperties("artel.demonstration")
data class DemonstrationProperties(val enabled: Boolean = false, val qaEnabled: Boolean = false)
@Configuration
@EnableConfigurationProperties(DemonstrationProperties::class)
class DemonstrationConfig

data class DemonstrationStart(val testCaseId: Long, val scenarioId: Long, val gameInstanceId: Long)
data class DemonstrationIdentity(val sessionId: String, val generation: Long)
data class DemonstrationControl(val sessionId: String, val generation: Long, val operation: String,
    val leaseMilliseconds: Long = 8000, val type: String = "DEMONSTRATION_CONTROL")
data class DemonstrationCapabilities(val protocolVersion: Int, val methods: List<String>)
data class DemonstrationControlResult(val sessionId: String, val generation: Long, val operation: String, val accepted: Boolean)
data class DemonstrationInstance(val id: Long, val name: String, val gameBuildId: Long?, val connected: Boolean, val protocolVersion: Int?)
data class DemonstrationCase(val id: Long, val scene: String?, val precondition: String?,
    @JsonProperty("test_step") val testStep: String?, val expected: String)
data class DemonstrationEvent(val id: Long, val kind: String, val payloadJson: String, val createdAt: Instant)
data class DemonstrationDetail(val id: UUID, val projectId: Long, val testCaseId: Long, val scenarioId: Long,
    val gameInstanceId: Long, val gameBuildId: Long, val status: String, val controlReady: Boolean,
    val generation: Long, val sourceHash: String?, val createdAt: Instant,
    val events: List<DemonstrationEvent>, val procedure: ProcedureRevision?)
data class EvidenceRegion(val x: Double, val y: Double, val width: Double, val height: Double)
data class DemonstrationAnnotation(val outcome: String, val observed: String = "", val expected: String = "",
    val explanation: String = "", val evidenceEventId: Long, val region: EvidenceRegion? = null)
data class EvidenceUpload(val dataUrl: String, val capturedAt: Instant, val width: Int, val height: Int)
data class DemonstrationEvidence(val objectKey: String, val capturedAt: Instant, val width: Int, val height: Int,
    val viewUrl: String? = null)

data class DemonstrationPulse(val objectKey: String, val sha256: String, val sizeBytes: Int)
data class ProcedureStep(val instruction: String, val target: String, val completionCondition: String, val evidenceEventIds: List<Long>)
data class ProcedureAssertion(val expected: String, val failure: String, @JsonProperty("when") val whenToCheck: String,
    val evidenceEventIds: List<Long>)
data class VerificationProcedure(val title: String, val preconditions: List<String>, val steps: List<ProcedureStep>,
    val assertions: List<ProcedureAssertion>, val observedOutcome: String, val facts: List<String>,
    val inferences: List<String>, val unresolvedQuestions: List<String>) {
    fun validate(eventIds: Set<Long>, approving: Boolean = false) {
        if (title.isBlank() || title.length > 500 || steps.size > 100 || assertions.size > 50 ||
            observedOutcome !in setOf("PASS", "FAIL", "INCONCLUSIVE")) throw BadRequestException("검증 절차 형식을 확인해 주세요.")
        val text = preconditions + facts + inferences + unresolvedQuestions +
            steps.flatMap { listOf(it.instruction, it.completionCondition) } +
            assertions.flatMap { listOf(it.expected, it.failure, it.whenToCheck) }
        if (text.any { it.isBlank() || it.length > 10000 } || steps.any { it.target.length > 10000 } ||
            steps.any { it.evidenceEventIds.size > 100 } || assertions.any { it.evidenceEventIds.size > 100 })
            throw BadRequestException("검증 절차의 문장과 근거 수를 확인해 주세요.")
        if (steps.any { it.instruction.isBlank() || it.completionCondition.isBlank() || it.evidenceEventIds.isEmpty() } ||
            assertions.any { it.expected.isBlank() || it.failure.isBlank() || it.whenToCheck.isBlank() || it.evidenceEventIds.isEmpty() } ||
            (steps.flatMap { it.evidenceEventIds } + assertions.flatMap { it.evidenceEventIds }).any { it !in eventIds })
            throw BadRequestException("검증 절차의 원본 근거가 필요합니다.")
        if ((approving || unresolvedQuestions.isEmpty()) && (steps.isEmpty() || assertions.isEmpty()))
            throw BadRequestException("검증 단계와 판정 조건이 필요합니다.")
        if (approving && unresolvedQuestions.isNotEmpty()) throw BadRequestException("미해결 질문을 확인한 뒤 승인해 주세요.")
    }
}
data class ProcedureRevision(val id: Long, val revision: Int, val status: String, val sourceHash: String, val procedure: VerificationProcedure)
data class ProcedureEdit(val expectedRevision: Int, val procedure: VerificationProcedure)
data class ProcedureVersionRequest(val expectedRevision: Int)
data class ProcedureSnapshot(val id: Long, val revision: Int, @JsonProperty("source_hash") val sourceHash: String, val procedure: VerificationProcedure)
data class ExtractionEvent(val id: Long, val kind: String, val payloadJson: String)
data class ExtractionImage(val eventId: Long, val url: String)
data class DemonstrationExtractRequest(val demonstrationId: String, val sourceHash: String,
    @JsonProperty("case") val testCase: DemonstrationCase, val events: List<ExtractionEvent>, val images: List<ExtractionImage>)
data class DemonstrationExtractResponse(val sourceHash: String, val procedure: VerificationProcedure)

data class DemonstrationInput(val type: String, val sequence: Long? = null, val method: String? = null,
    val params: List<com.fasterxml.jackson.databind.JsonNode> = emptyList())
data class DemonstrationReply(val type: String, val sequence: Long? = null, val eventId: Long? = null,
    val code: String? = null, val message: String? = null)
data class DemonstrationResultEnvelope(val requestId: Long? = null, val id: Long? = null)
data class DemonstrationControlEvent(val operation: String, val accepted: Boolean? = null)
data class DemonstrationCommandRecord(val requestId: Long, val sequence: Long, val method: String, val params: List<com.fasterxml.jackson.databind.JsonNode>, val status: String = "REQUESTED")
