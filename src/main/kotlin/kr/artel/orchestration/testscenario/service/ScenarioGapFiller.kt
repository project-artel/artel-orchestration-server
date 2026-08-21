package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.agent.PhrasedStep
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
 * 것은 계산이지 판단이 아니다 — 자리를 정하는 데 모델을 부를 일이 아니다.
 *
 * **문장은 모델이 다듬는다.** 사용자가 적은 말은 앞뒤 스텝과 결이 다르고 한 문장에 동작이 둘
 * 들어 있기도 하다. 그래서 앞뒤 스텝을 들려주고 다듬은 줄을 받아 오는데([phrase]), 그 결과가
 * **여러 줄일 수 있고 빈 목록일 수도 있다** — 빈 목록은 "그 말은 통과 방법이 아니다"라는 답이라,
 * 그때는 알림을 그대로 두고 그 말은 대화로 넘어간다.
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
     * [blockedBy] 구간의 알림 블록을 [phrase] 가 돌려준 스텝들로 바꾼다.
     *
     * @param phrase 알림 블록의 앞뒤 스텝을 받아 그 자리에 넣을 줄들을 돌려준다. 빈 목록이면 그
     *   블록은 건드리지 않는다.
     * @return 실제로 넣은 스텝 수. 0 이면 그 구간이 이미 채워졌거나(사용자가 손으로) 넣을 말이
     *   없다는 뜻이라, 부르는 쪽은 평소대로 모델에게 넘기면 된다.
     */
    suspend fun fill(
        runId: Long,
        blockedBy: String,
        phrase: suspend (before: String, after: String) -> List<PhrasedStep>,
    ): Int {
        var filled = 0
        for (link in runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()) {
            val scenario = scenarioRepository.findById(link.testScenarioId) ?: continue
            val draft = scenario.toDraft(objectMapper)
            val steps = draft.steps.toMutableList()
            var touched = false
            // 뒤에서부터 훑는다. 한 블록이 여러 줄로 늘어나도 아직 보지 않은 앞쪽 인덱스는
            // 그대로라, 자리를 다시 세지 않아도 된다.
            for (index in steps.indices.reversed()) {
                val step = steps[index]
                if (step.stepKind != ScenarioStepKind.GAP || step.stepUnknownReason != blockedBy) continue
                val lines = phrase(
                    steps.getOrNull(index - 1)?.action.orEmpty(),
                    steps.getOrNull(index + 1)?.action.orEmpty(),
                )
                if (lines.isEmpty()) continue
                steps[index] = human(lines.first(), step.hint)
                lines.drop(1).forEachIndexed { offset, line ->
                    steps.add(index + 1 + offset, human(line, null))
                }
                filled += lines.size
                touched = true
            }
            if (!touched) continue
            scenarioRepository.save(scenario.withDraft(draft.copy(steps = steps), objectMapper))
        }
        if (filled > 0) logger.info("미상 구간을 사용자 말로 채움 [runId={}] {} · {}건", runId, blockedBy, filled)
        return filled
    }

    private fun human(line: PhrasedStep, hint: String?) = ScenarioStep(
        action = line.action,
        // 사람이 알려준 길이지 케이스 검증이 아니다. 판정 대상이 되면 실행이 이 줄에 통과/실패를
        // 매기고, 그건 사용자가 말한 것을 채점하는 셈이 된다.
        caseId = null,
        hint = hint,
        input = line.input?.ifBlank { null },
        stepSource = ScenarioStepSource.HUMAN,
        stepKind = ScenarioStepKind.ACTION,
    )
}
