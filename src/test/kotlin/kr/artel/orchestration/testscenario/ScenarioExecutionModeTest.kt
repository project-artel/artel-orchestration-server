package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.service.InvalidFreeScenarioException
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import kr.artel.orchestration.testscenario.service.ScenarioExecutionRules
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * ARTEL-1028 — 시나리오 execution_mode. FREE 저장 규칙, 실행 계약 조립, 와이어 이름을 확인한다.
 * DB 왕복은 [kr.artel.orchestration.TestScenarioRepositoryTest] 에 있다.
 */
class ScenarioExecutionModeTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private fun free(vararg steps: ScenarioStep) =
        ScenarioDraft(title = "묶음", steps = steps.toList(), executionMode = ScenarioExecutionMode.FREE)

    @Test
    fun `FREE 초안은 case_id 가 모두 있고 겹치지 않고 ACTION 이면 통과한다`() {
        assertThatCode {
            ScenarioExecutionRules.validate(
                free(
                    ScenarioStep(caseId = 1L),
                    ScenarioStep(caseId = 2L, stepKind = ScenarioStepKind.ACTION),
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `FREE 초안에 step 이 하나도 없어도 통과한다`() {
        assertThatCode { ScenarioExecutionRules.validate(free()) }.doesNotThrowAnyException()
    }

    @Test
    fun `FREE 초안에 case_id 가 없는 step 이 있으면 거절한다`() {
        assertThatThrownBy {
            ScenarioExecutionRules.validate(free(ScenarioStep(caseId = 1L), ScenarioStep(action = "눌러")))
        }.isInstanceOf(InvalidFreeScenarioException::class.java)
            .extracting("code").isEqualTo("invalid_free_scenario")
    }

    @Test
    fun `FREE 초안에 같은 case_id 가 두 번 나오면 거절한다`() {
        assertThatThrownBy {
            ScenarioExecutionRules.validate(free(ScenarioStep(caseId = 3L), ScenarioStep(caseId = 3L)))
        }.isInstanceOf(InvalidFreeScenarioException::class.java)
    }

    @Test
    fun `FREE 초안에 GAP step 이 있으면 거절한다`() {
        assertThatThrownBy {
            ScenarioExecutionRules.validate(free(ScenarioStep(caseId = 1L, stepKind = ScenarioStepKind.GAP)))
        }.isInstanceOf(InvalidFreeScenarioException::class.java)
    }

    @Test
    fun `execution_mode 를 보내지 않은 저장은 FREE 시나리오를 FREE 로 둔다`() {
        val stored = TestScenarioEntity(id = 1L, projectId = 1L, executionMode = ScenarioExecutionMode.FREE)
        val unstated = ScenarioDraft(title = "CLI 가 고친 제목", steps = listOf(ScenarioStep(caseId = 1L)))

        assertThat(stored.withDraft(unstated, objectMapper).executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(stored.withDraft(unstated.copy(executionMode = ScenarioExecutionMode.ORDERED), objectMapper).executionMode)
            .isEqualTo(ScenarioExecutionMode.ORDERED)
    }

    @Test
    fun `ORDERED 초안은 case_id 없는 step 과 GAP 도 검사하지 않는다`() {
        val draft = ScenarioDraft(
            steps = listOf(ScenarioStep(action = "이동"), ScenarioStep(caseId = 1L, stepKind = ScenarioStepKind.GAP)),
        )
        assertThatCode { ScenarioExecutionRules.validate(draft) }.doesNotThrowAnyException()
    }

    @Test
    fun `execution_mode 가 없는 요청은 값이 없고 ORDERED 로 읽히며 와이어 이름은 execution_mode 다`() {
        val missing = objectMapper.readValue<ScenarioDraft>("""{"title":"t","steps":[]}""")
        assertThat(missing.executionMode).isNull()
        assertThat(missing.mode).isEqualTo(ScenarioExecutionMode.ORDERED)
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(missing)).has("mode")).isFalse()

        val parsed = objectMapper.readValue<ScenarioDraft>("""{"execution_mode":"FREE"}""")
        assertThat(parsed.executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(parsed)).get("execution_mode").asText())
            .isEqualTo("FREE")
    }

    @Test
    fun `FREE 조립은 빈 action 을 TC 의 테스트 스텝으로 채우고 execution_mode 를 싣는다`(): Unit = runBlocking {
        val scenario = composition().agentScenario(
            free(
                ScenarioStep(caseId = 10L),
                ScenarioStep(action = "직접 쓴 행위", caseId = 11L, expectedPassed = false),
            ),
        )
        assertThat(scenario.executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(scenario.steps.map { it.action }).containsExactly("구매 버튼을 누른다", "직접 쓴 행위")
        assertThat(scenario.steps[0].case?.expected).isEqualTo("아이템이 들어온다")
        assertThat(objectMapper.writeValueAsString(scenario)).doesNotContain("expected_passed")
    }

    @Test
    fun `ORDERED 조립은 빈 action 을 채우지 않고 execution_mode ORDERED 만 더해진다`(): Unit = runBlocking {
        val scenario = composition().agentScenario(
            ScenarioDraft(title = "순서", steps = listOf(ScenarioStep(action = "", caseId = 10L))),
        )
        assertThat(scenario.executionMode).isEqualTo(ScenarioExecutionMode.ORDERED)
        assertThat(scenario.steps[0].action).isEmpty()
        val keys = objectMapper.readTree(objectMapper.writeValueAsString(scenario)).fieldNames().asSequence().toList()
        assertThat(keys).containsExactly("title", "description", "steps", "execution_mode")
    }

    private fun composition(): ScenarioCompositionService {
        val cases = listOf(
            TestCaseEntity(id = 10L, projectId = 1L, scene = "상점", step = "구매 버튼을 누른다", expectedValue = "아이템이 들어온다"),
            TestCaseEntity(id = 11L, projectId = 1L, scene = "상점", step = "판매 버튼을 누른다", expectedValue = "골드가 늘어난다"),
        )
        val repository = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(TestCaseRepository::class.java),
        ) { _, method, _ ->
            check(method.name == "findAllById") { "예상 밖 호출: ${method.name}" }
            flowOf(*cases.toTypedArray())
        } as TestCaseRepository
        // 버전을 고르지 않은 조립(최신)은 버전 몫과 게임 빌드를 읽지 않는다(ARTEL-1069).
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
