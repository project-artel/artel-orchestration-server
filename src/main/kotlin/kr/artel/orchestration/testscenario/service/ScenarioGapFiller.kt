package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 사용자가 알려준 방법을 **그 자리에** 넣는다(ARTEL-487).
 *
 * 되묻고 답을 받았는데, 그 답을 모델에게 넘겨 다시 쓰게 했더니 엉뚱한 자리에 들어갔다 — 실측에서
 * "StoryScene→Map_scene 을 어떻게 가나요"에 답하자 모델이 그 문장을 **6번 스텝**으로 넣고 8번의
 * 경고는 그대로 두었다. 사용자가 보기에는 답을 했는데 아무 일도 안 일어난 것과 같다.
 *
 * **자리는 코드가 안다.** 어느 구간을 물었는지가 질문 id 에 있고(`gap:StoryScene→Map_scene`),
 * 그 구간의 알림 블록에는 같은 이름이 `step_unknown_reason` 으로 붙어 있다. 그러면 바꿔 끼우는
 * 것은 계산이지 판단이 아니다 — 모델을 부를 일이 아니다(부르면 값도 들고 결과도 흔들린다).
 *
 * 넣는 스텝은 [ScenarioStepSource.HUMAN] 이다. 명세가 모르는 것을 사람이 알려준 자리라 코드가
 * 다시 덮지 않는다.
 */
@Service
class ScenarioGapFiller(
    private val scenarioRepository: TestScenarioRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(ScenarioGapFiller::class.java)

    /**
     * [blockedBy] 구간의 알림 블록을 [howTo] 로 바꾼다.
     *
     * @return 바꾼 블록 수. 0 이면 그 구간이 이미 채워졌거나 사용자가 손으로 지운 것이라, 부르는
     *   쪽은 평소대로 모델에게 넘기면 된다.
     */
    suspend fun fill(runId: Long, blockedBy: String, howTo: String): Int {
        if (howTo.isBlank()) return 0
        var filled = 0
        for (link in runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()) {
            val scenario = scenarioRepository.findById(link.testScenarioId) ?: continue
            val draft = scenario.toDraft(objectMapper)
            var touched = false
            val steps = draft.steps.map { step ->
                if (step.stepKind != ScenarioStepKind.GAP || step.stepUnknownReason != blockedBy) step
                else {
                    touched = true
                    filled++
                    ScenarioStep(
                        action = howTo,
                        // 사람이 알려준 길이지 케이스 검증이 아니다. 판정 대상이 되면 실행이 이
                        // 줄에 통과/실패를 매기고, 그건 사용자가 말한 것을 채점하는 셈이 된다.
                        caseId = null,
                        hint = step.hint,
                        stepSource = ScenarioStepSource.HUMAN,
                        stepKind = ScenarioStepKind.ACTION,
                    )
                }
            }
            if (!touched) continue
            scenarioRepository.save(scenario.withDraft(draft.copy(steps = steps), objectMapper))
        }
        if (filled > 0) logger.info("미상 구간을 사용자 말로 채움 [runId={}] {} · {}건", runId, blockedBy, filled)
        return filled
    }
}
