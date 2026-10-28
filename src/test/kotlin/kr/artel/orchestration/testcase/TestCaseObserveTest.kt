package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.dto.TestCaseObserve
import kr.artel.orchestration.testcase.dto.observeOrNull
import kr.artel.orchestration.testcase.dto.toTestCaseDetailResponse
import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * ARTEL-1280 — test case 의 `observe`. `metadata.observe` 에 쓰는 규칙, 응답으로 읽는 규칙, agent case ref 로 건너는
 * 모양을 확인한다. DB 를 거치는 수정 경로는 [kr.artel.orchestration.game.GameFunctionsIntegrationTest] 에 있다.
 */
class TestCaseObserveTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private fun case(metadata: String) =
        TestCaseEntity(id = 10L, projectId = 1L, scene = "상점", step = "구매", expectedValue = "골드가 줄어든다", metadata = Json.of(metadata))

    @Test
    fun `빈 이름과 null 은 400 이고 공백을 다듬고 같은 이름은 한 번만 남긴다`() {
        assertThat(TestCaseObserve.validated(listOf(" Game.Player::hp ", "Progress::stage", "Game.Player::hp")))
            .containsExactly("Game.Player::hp", "Progress::stage")
        for (bad in listOf(listOf("hp", ""), listOf("hp", "  "), listOf(null, "hp"))) {
            assertThatThrownBy { TestCaseObserve.validated(bad) }
                .describedAs(bad.toString())
                .isInstanceOf(BadRequestException::class.java)
                .hasMessage("observe must not contain a blank name")
        }
    }

    @Test
    fun `write 는 observe 키만 바꾸고 다른 키는 그대로 둔다`() {
        val before = Json.of("""{"origin":"content-map","source":{"spec_id":"s1"}}""")

        val written = TestCaseObserve.write(before, listOf("Game.Player::hp"), objectMapper)

        assertThat(objectMapper.readTree(written.asString())).isEqualTo(
            objectMapper.readTree("""{"origin":"content-map","source":{"spec_id":"s1"},"observe":["Game.Player::hp"]}""")
        )
    }

    @Test
    fun `빈 목록은 observe 키를 지우고 객체가 아닌 metadata 는 빈 객체로 본다`() {
        val cleared = TestCaseObserve.write(Json.of("""{"origin":"content-map","observe":["hp"]}"""), emptyList(), objectMapper)
        assertThat(objectMapper.readTree(cleared.asString())).isEqualTo(objectMapper.readTree("""{"origin":"content-map"}"""))

        val fromNull = TestCaseObserve.write(Json.of("null"), listOf("hp"), objectMapper)
        assertThat(objectMapper.readTree(fromNull.asString())).isEqualTo(objectMapper.readTree("""{"observe":["hp"]}"""))
    }

    @Test
    fun `observeOrNull 은 문자열 배열만 읽고 없거나 다른 모양이면 null 이다`() {
        assertThat(case("""{"source":{"a":1},"observe":["Game.Player::hp","Progress::stage"],"origin":"x"}""").observeOrNull())
            .containsExactly("Game.Player::hp", "Progress::stage")
        assertThat(case("""{"observe":[]}""").observeOrNull()).isEmpty()
        assertThat(case("""{}""").observeOrNull()).isNull()
        assertThat(case("""{"observe":"hp"}""").observeOrNull()).isNull()
        assertThat(case("""{"observe":["hp",1]}""").observeOrNull()).isNull()
        assertThat(case("""{"observe":null}""").observeOrNull()).isNull()
        assertThat(case("""[]""").observeOrNull()).isNull()
    }

    @Test
    fun `목록 응답과 단건 응답이 같은 observe 를 낸다`() {
        val entity = case("""{"observe":["Game.Player::hp"]}""")
        assertThat(entity.toTestCaseResponse().observe).containsExactly("Game.Player::hp")
        assertThat(entity.toTestCaseDetailResponse(objectMapper).observe).containsExactly("Game.Player::hp")
        assertThat(case("""{}""").toTestCaseResponse().observe).isNull()
    }

    @Test
    fun `수정으로 쓴 observe 가 응답과 agent case ref 까지 같은 값으로 간다`(): Unit = runBlocking {
        val updated = case("""{"origin":"content-map"}""").let {
            it.copy(metadata = TestCaseObserve.write(it.metadata, TestCaseObserve.validated(listOf("Game.Player::hp", "Progress::stage")), objectMapper))
        }
        assertThat(updated.toTestCaseResponse().observe).containsExactly("Game.Player::hp", "Progress::stage")

        val agentCase = composition(updated).agentScenario(draftFor(10L)).steps.single().case!!
        assertThat(agentCase.observe).containsExactly("Game.Player::hp", "Progress::stage")
        assertThat(objectMapper.valueToTree<JsonNode>(agentCase)["observe"])
            .isEqualTo(objectMapper.readTree("""["Game.Player::hp","Progress::stage"]"""))
    }

    @Test
    fun `observe 를 정하지 않은 TC 는 agent case ref 에 키를 싣지 않는다`(): Unit = runBlocking {
        val agentCase = composition(case("""{"origin":"content-map"}""")).agentScenario(draftFor(10L)).steps.single().case!!
        assertThat(agentCase.observe).isNull()
        assertThat(objectMapper.valueToTree<JsonNode>(agentCase).has("observe")).isFalse()
    }

    private fun draftFor(caseId: Long) =
        ScenarioDraft(title = "t", description = "", steps = listOf(ScenarioStep(action = "1", caseId = caseId)))

    /** [kr.artel.orchestration.testscenario.ScenarioExecutionModeTest] 와 같은 조립. 최신 조립은 버전 몫을 읽지 않는다. */
    private fun composition(vararg cases: TestCaseEntity): ScenarioCompositionService {
        val repository = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(TestCaseRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "findAllById") { "예상 밖 호출: ${method.name}" }
            flowOf(*cases)
        } as TestCaseRepository
        val untouched = { type: Class<*> ->
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, _ ->
                error("예상 밖 호출: ${method.name}")
            }
        }
        val reader = TestCaseReader(
            repository,
            untouched(TestCaseVersionRepository::class.java) as TestCaseVersionRepository,
            untouched(GameBuildRepository::class.java) as GameBuildRepository,
        )
        return ScenarioCompositionService(reader)
    }
}
