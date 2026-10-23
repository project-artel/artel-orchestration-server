package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO

class DemonstrationValidationTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `실제 PNG만 허용하고 신고된 크기가 다르면 거절한다`() {
        val bytes = demonstrationPng()
        val request = EvidenceUpload(dataUrl(bytes), Instant.now(), 1, 1)
        assertThat(decodeEvidence(request)).containsExactly(*bytes)
        assertThatThrownBy { decodeEvidence(request.copy(width = 2)) }.isInstanceOf(BadRequestException::class.java)
        val headerOnly = bytes.copyOf(24)
        assertThatThrownBy { decodeEvidence(request.copy(dataUrl = dataUrl(headerOnly))) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `손상된 base64와 미래 시각 및 초과 화면 크기를 거절한다`() {
        val request = EvidenceUpload(dataUrl(demonstrationPng()), Instant.now(), 1, 1)
        assertThatThrownBy { decodeEvidence(request.copy(dataUrl = "data:image/png;base64,%%%%")) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { decodeEvidence(request.copy(capturedAt = Instant.now().plusSeconds(120))) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { decodeEvidence(request.copy(width = 1921)) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `좌표와 버튼은 지원 범위 안의 인자만 받는다`() {
        val supported = listOf("move_mouse", "mouse_down", "key_down", "reset_game")
        validateInput("move_mouse", listOf(mapper.readTree("0"), mapper.readTree("1")), supported)
        validateInput("mouse_down", listOf(mapper.readTree("2")), supported)
        for (invalid in listOf("-1", "1.01", "\"0.5\"")) {
            assertThatThrownBy { validateInput("move_mouse", listOf(mapper.readTree(invalid), mapper.readTree("0")), supported) }
                .isInstanceOf(BadRequestException::class.java)
        }
        assertThatThrownBy { validateInput("mouse_down", listOf(mapper.readTree("3")), supported) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { validateInput("mouse_down", listOf(mapper.readTree("1.5")), supported) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { validateInput("reset_game", emptyList(), supported) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { validateInput("key_up", listOf(mapper.readTree("\"Space\"")), supported) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `SDK 결과의 id와 success가 없으면 적용 성공으로 추정하지 않는다`() {
        assertThat(demonstrationResultStatus(mapper.readTree("""{"results":[{"id":7,"success":true,"error":""}]}"""), 7)).isEqualTo("APPLIED")
        assertThat(demonstrationResultStatus(mapper.readTree("""{"results":[{"id":7,"success":false,"error":"expired"}]}"""), 7)).isEqualTo("FAILED")
        for (payload in listOf("{}", """{"results":[]}""", """{"results":[{"id":8,"success":true}]}""",
            """{"results":[{"id":7,"success":"true"}]}""", """{"results":[{"id":7}]}""")) {
            assertThat(demonstrationResultStatus(mapper.readTree(payload), 7)).isEqualTo("UNKNOWN")
        }
    }

    @Test
    fun `다른 시연 event를 절차 근거로 사용할 수 없다`() {
        val procedure = VerificationProcedure("구매 검증", emptyList(),
            listOf(ProcedureStep("구매", "구매 버튼", "구매 완료", listOf(10))),
            listOf(ProcedureAssertion("아이템 추가", "아이템 없음", "구매 후", listOf(11))),
            "FAIL", emptyList(), emptyList(), emptyList())
        procedure.validate(setOf(10, 11), true)
        assertThatThrownBy { procedure.validate(setOf(10), true) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { procedure.copy(unresolvedQuestions = listOf("실패 조건 확인 필요")).validate(setOf(10, 11), true) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `판정과 화면만으로 annotation을 남기고 설명은 선택할 수 있다`() {
        val annotation = mapper.readValue("""{"outcome":"FAIL","evidenceEventId":7}""", DemonstrationAnnotation::class.java)
        validateAnnotation(annotation)
        assertThat(listOf(annotation.observed, annotation.expected, annotation.explanation)).containsOnly("")
        for (oversized in listOf(annotation.copy(observed = "x".repeat(10001)),
            annotation.copy(expected = "x".repeat(10001)), annotation.copy(explanation = "x".repeat(10001)))) {
            assertThatThrownBy { validateAnnotation(oversized) }.isInstanceOf(BadRequestException::class.java)
        }
        assertThatThrownBy { validateAnnotation(annotation.copy(outcome = "UNKNOWN")) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `미완성 초안은 질문을 보존하지만 빈 문장과 과도한 근거를 받지 않는다`() {
        val draft = VerificationProcedure("구매 검증", emptyList(), emptyList(), emptyList(),
            "INCONCLUSIVE", emptyList(), emptyList(), listOf("완료 조건 확인 필요"))
        draft.validate(emptySet())
        assertThatThrownBy { draft.validate(emptySet(), true) }.isInstanceOf(BadRequestException::class.java)
        for (invalid in listOf(draft.copy(facts = listOf(" ")),
            draft.copy(inferences = listOf("x".repeat(10001))),
            draft.copy(steps = listOf(ProcedureStep("구매", "버튼", "완료", List(101) { 1L }))))) {
            assertThatThrownBy { invalid.validate(setOf(1L)) }.isInstanceOf(BadRequestException::class.java)
        }
    }

    private fun dataUrl(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
}

internal fun demonstrationPng(): ByteArray = ByteArrayOutputStream().use { output ->
    ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", output)
    output.toByteArray()
}
