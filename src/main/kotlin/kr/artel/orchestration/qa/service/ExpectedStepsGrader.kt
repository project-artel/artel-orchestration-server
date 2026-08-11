package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.repository.QaTryScoreRepository
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 이 채점자의 이름. `qa_try_score.grader`에 그대로 들어간다. */
const val EXPECTED_STEPS_GRADER = "expected-steps"

/**
 * 채점 규칙의 버전. **혼동행렬의 정의나 미보고 처리를 바꾸면 올린다.** 올리면 같은 런에 새 행이
 * 서고 옛 판정은 그대로 남아, 규칙이 바뀐 전후를 대조할 수 있다.
 */
const val EXPECTED_STEPS_GRADER_VERSION = "1"

/**
 * 사람이 단 기대 판정과 에이전트가 보고한 판정을 대조하는 **결정적** 채점자(ARTEL-301).
 *
 * ## 왜 필요한가
 *
 * QA 에이전트의 스텝 판정은 자기채점이다. 에이전트가 "이 스텝 통과"라고 말하면 그 값이 그대로
 * 지표가 된다(ARTEL-299가 승격한 `steps_passed`가 바로 그 값이다). 관대한 모델이 점수가 높게 나오고,
 * "전부 통과"라고 답하는 전략이 만점이다. 이 숫자로 모델 순위를 매기면 틀린다.
 *
 * 저작 시점에 사람이 각 스텝에 기대 판정을 달아 두면 그 게임이 끝난다. 실패해야 하는 스텝이 섞여
 * 있으면 "전부 통과" 전략이 **최악** 점수가 된다.
 *
 * ## 왜 스칼라 하나로 접지 않나
 *
 * 오탐(멀쩡한 것을 실패라 함)과 미탐(실패해야 할 것을 통과라 함)은 무게가 다르다. QA 에이전트에게
 * 미탐이 훨씬 나쁘다 — 못 찾은 버그는 출시된다. 정확도 하나로 접으면 그 방향이 사라지고, 미탐이
 * 많은 모델과 오탐이 많은 모델이 같은 점수로 보인다. 그래서 네 칸을 그대로 남긴다. 스칼라가 필요한
 * 화면은 이 행렬에서 자기가 원하는 가중치로 파생시키면 된다.
 *
 * ## 미보고는 세 번째 상태다
 *
 * 런이 죽으면 그 스텝에는 판정 자체가 없다. 일치로 세면 일찍 죽은 런이 만점이 되고, 불일치로 세면
 * 죽었다는 사실이 스텝 수만큼 이중 계산된다. 어느 쪽도 아닌 칸으로 분리하고 커버리지를 함께 낸다
 * (ARTEL-299가 `verdict_known`을 축별 집계에 실은 것과 같은 규율).
 *
 * ## 지표 컬럼을 승격하지 않는 이유
 *
 * V25는 "컬럼은 GROUP BY용 사본이고 진실은 JSONB"라고 했고, 승격은 **집계가 실제로 그 축으로 팔 때**
 * 하는 것이다. 지금 축별 집계는 점수를 읽지 않는다 — 점수 화면 자체가 후속이다. 어느 칸으로 접을지
 * 모르는 채로 컬럼을 만들면 소비처가 생길 때 다시 골라야 하고, 그 사이 모든 행에서 0으로 읽히는
 * 컬럼이 하나 늘 뿐이다(V27이 `cited`를 DEFAULT false로 두지 않은 것과 같은 오류다). 점수 화면이
 * 어떤 컷으로 그룹핑하는지 본 뒤에 필요한 것만 올린다.
 *
 * ## 실패는 삼킨다
 *
 * 종료 경로 네 곳에서 불리고, 그중 하나는 WebSocket 수신 체인 안이다. 여기서 나간 예외는 소켓을
 * 닫고 이미 끝난 런을 실패로 뒤집는다. 채점은 사후 계산이고 입력(qa_log·시나리오)은 그대로 남아
 * 있으므로 나중에 다시 낼 수 있다 — 런을 죽일 이유가 없다. 호출부 넷이 각자 try/catch를 두는 대신
 * 여기 한 곳에서 막는 것은, 규칙을 네 번 적어 두면 그중 하나가 언젠가 빠지기 때문이다.
 * `CancellationException`은 오류가 아니라 취소 신호라 반드시 다시 던진다.
 */
@Service
class ExpectedStepsGrader(
    private val tryRepository: QaTryRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val logRepository: QaLogRepository,
    private val scoreRepository: QaTryScoreRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ExpectedStepsGrader::class.java)

    /**
     * [qaTryId]를 채점해 `qa_try_score`에 한 줄 남긴다. 라벨이 하나도 없는 시나리오는 채점 대상이
     * 아니므로 행을 만들지 않는다 — 빈 행렬을 남기면 "채점했는데 전부 0"과 "채점할 것이 없었다"가
     * 같아진다.
     */
    suspend fun grade(qaTryId: Long) {
        try {
            gradeOrThrow(qaTryId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("qa_try {} 채점 실패 — 런은 그대로 종료한다: {}", qaTryId, error.message, error)
        }
    }

    private suspend fun gradeOrThrow(qaTryId: Long) {
        val qaTry = tryRepository.findById(qaTryId) ?: return
        val scenario = scenarioRepository.findById(qaTry.testScenarioId) ?: return
        // 시나리오 본문은 title/description/steps 컬럼에 있다(ARTEL-291). 읽는 방법은 저장 계층이
        // 아는 것으로 통일한다 — 여기서 컬럼을 직접 파싱하면 저장 형태가 또 바뀔 때 두 곳이 갈린다.
        val steps = scenario.toDraft(objectMapper).steps

        // 스텝 번호는 1부터다(에이전트의 QaStepResult.step과 같은 기준). 라벨이 null인 스텝은
        // 여기서 빠지고, 그래서 분모에도 들어가지 않는다.
        val expected = steps.mapIndexedNotNull { index, step ->
            step.expectedPassed?.let { ExpectedStep(step = index + 1, caseId = step.caseId, expectedPassed = it) }
        }
        if (expected.isEmpty()) return

        val reported = reportedVerdicts(qaTryId)
        var correctPass = 0
        var falseAlarm = 0
        var miss = 0
        var correctFail = 0
        var unreported = 0
        for (label in expected) {
            when (reported[label.step]) {
                null -> unreported++
                true -> if (label.expectedPassed) correctPass++ else miss++
                false -> if (label.expectedPassed) falseAlarm++ else correctFail++
            }
        }

        val detail = objectMapper.createObjectNode().apply {
            put("scenario_id", qaTry.testScenarioId)
            put("labeled_steps", expected.size)
            put("reported", expected.size - unreported)
            put("unreported", unreported)
            putObject("matrix").apply {
                put("correct_pass", correctPass)
                put("false_alarm", falseAlarm)
                put("miss", miss)
                put("correct_fail", correctFail)
            }
            // 채점에 **실제로 쓴** 기대 벡터를 그대로 박는다. 시나리오 라벨은 나중에 고쳐지고,
            // grader_version만으로는 무엇과 대조했는지 알 수 없다 — 라벨은 시나리오마다 다르기
            // 때문이다. 이 스냅샷이 없으면 옛 점수와 새 점수를 비교할 근거가 사라진다.
            set<com.fasterxml.jackson.databind.JsonNode>(
                "expected",
                objectMapper.valueToTree(expected)
            )
        }

        scoreRepository.insertIfAbsent(
            qaTryId = qaTryId,
            grader = EXPECTED_STEPS_GRADER,
            graderVersion = EXPECTED_STEPS_GRADER_VERSION,
            detail = objectMapper.writeValueAsString(detail)
        )
    }

    /**
     * 이 런이 실제로 보고한 스텝 판정. 없는 스텝은 맵에 없고, 그것이 곧 미보고다.
     *
     * 같은 스텝이 두 번 보고되면 **나중 것이 이긴다**. 에이전트가 스텝을 다시 시도하고 다시 판정할 수
     * 있고, 그때 그 스텝에 대한 최종 입장은 마지막 것이다.
     */
    private suspend fun reportedVerdicts(qaTryId: Long): Map<Int, Boolean> {
        val verdicts = mutableMapOf<Int, Boolean>()
        for (log in logRepository.findStepVerdicts(qaTryId).toList()) {
            val payload = objectMapper.readTree(log.payload.asString())
            val step = payload.get("step")?.takeIf { it.isIntegralNumber }?.asInt() ?: continue
            val status = payload.get("status")?.takeIf { it.isTextual }?.asText() ?: continue
            verdicts[step] = status == "COMPLETED"
        }
        return verdicts
    }
}

/**
 * 채점에 쓴 기대 라벨 하나. `qa_try_score.detail.expected[]`로 그대로 직렬화된다.
 *
 * `caseId`를 함께 박는 이유: 검증 스텝의 라벨이 곧 그 TC의 기대 판정이라, 나중에 TC 단위로 다시
 * 접으려면 어느 스텝이 어느 TC였는지가 그 시점 기준으로 남아 있어야 한다.
 */
data class ExpectedStep(
    val step: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("case_id") val caseId: Long?,
    @com.fasterxml.jackson.annotation.JsonProperty("expected_passed") val expectedPassed: Boolean
)
