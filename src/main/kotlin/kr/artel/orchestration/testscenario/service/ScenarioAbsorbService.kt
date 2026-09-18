package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * "이 둘을 합쳐 줘" 를 끝까지 해내는 자리 — 합친 본문을 저장한 뒤, **흡수된 쪽을 런에서 걷어낸다.**
 *
 * 없을 때 무슨 일이 났는지가 이 클래스의 존재 이유다. 수정 갈래는 시나리오 하나만 써낼 수 있어서
 * 합치기 요청을 받으면 한쪽에 둘의 내용을 다 적고 끝났고, 원본 두 개 중 하나가 그대로 남아
 * 같은 흐름이 두 벌이 됐다. 쓰는 쪽에 칸을 하나 더 주는 것만으로는 부족하다 — 지우는 것은
 * 되돌릴 수 없으니, 지워도 되는지는 코드가 센다.
 *
 * **세 가지를 모두 지나야 걷어낸다.**
 *
 * 1. 이 런에 실제로 담긴 시나리오일 것. 남의 런, 없는 번호는 조용히 무시한다.
 * 2. **흡수된 쪽이 검증하던 케이스가 남는 쪽에 전부 있을 것.** 합치기는 두 흐름을 한 흐름으로
 *    만드는 일이지 한쪽을 버리는 일이 아니다. 케이스가 하나라도 빠지면 그것은 합치기가 아니라
 *    소실이라, 남기고 이유를 말한다.
 * 3. QA 실행 이력이 없을 것. 실행된 시나리오에는 기록과 발견된 이슈가 붙어 있고, 그것이
 *    합치기의 부수 효과로 사라지면 안 된다([TestScenarioService.delete] 가 막는 것과 같은 이유).
 *
 * 통과한 것은 **이 런의 조합에서 뺀다.** 다른 런에도 담긴 시나리오면 거기서는 그대로 산다 —
 * 한 런을 정리하다 남의 조합을 무너뜨리지 않는다([TestRunScenarioRepository.findScenarioIdsOnlyInRun]
 * 이 같은 선을 긋는다). 어디에도 안 남으면 본체까지 지운다. 남겨 두면 어느 런에도 없는 시나리오가
 * 커버리지를 계속 "담긴 것" 으로 세기 때문이다.
 */
@Service
class ScenarioAbsorbService(
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val qaTryRepository: QaTryRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
) {
    private val logger = LoggerFactory.getLogger(ScenarioAbsorbService::class.java)

    /**
     * @property absorbed 실제로 걷어낸 시나리오 제목.
     * @property kept 걷어내지 못한 것 — 제목과 이유가 한 문장에 담겨 있어 그대로 전할 수 있다.
     */
    data class Outcome(
        val absorbed: List<String> = emptyList(),
        val kept: List<String> = emptyList(),
    )

    /**
     * [keeper] 로 합쳐졌다고 선언된 [absorbedIds] 를 [runId] 에서 걷어낸다.
     *
     * [keeper] 는 **저장된 최종본**이어야 한다. 모델이 낸 것으로 케이스를 세면 코드가 나누기·메우기로
     * 손댄 뒤의 실제 본문과 달라, 실제로는 빠진 케이스를 있다고 세고 원본을 지울 수 있다.
     */
    suspend fun absorb(
        runId: Long,
        keeperId: Long?,
        keeper: ScenarioResult,
        absorbedIds: List<Long>,
    ): Outcome {
        val wanted = absorbedIds.filter { it != keeperId }.distinct()
        if (wanted.isEmpty()) return Outcome()

        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        val inRun = links.map { it.testScenarioId }.toSet()
        val covered = keeper.steps.mapNotNull { it.caseId }.toSet()

        val absorbed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (id in wanted) {
            if (id !in inRun) continue // 이 런의 것이 아니면 없던 말로 친다 — 유령 번호 방어
            val scenario = scenarioRepository.findById(id) ?: continue
            val draft = scenario.toDraft(objectMapper)
            val mine = draft.steps.mapNotNull { it.caseId }.toSet()
            val missing = mine - covered
            if (missing.isNotEmpty()) {
                kept += "'${draft.title}' 은 합친 쪽에 담기지 않은 검증이 남아 있어 그대로 두었습니다"
                continue
            }
            if (qaTryRepository.countByTestScenarioId(id) > 0) {
                kept += "'${draft.title}' 은 QA 실행 이력이 있어 지우지 않고 그대로 두었습니다"
                continue
            }
            val elsewhere = runScenarioRepository.findByTestScenarioId(id).toList()
                .any { it.testRunId != runId }
            transactionalOperator.executeAndAwait {
                links.filter { it.testScenarioId == id }.forEach { runScenarioRepository.delete(it) }
                if (!elsewhere) scenarioRepository.deleteById(id)
            }
            logger.info(
                "합치면서 걷어냄 [runId={}, testScenarioId={}] {} — {}",
                runId, id, draft.title, if (elsewhere) "다른 런에 남음(조합만 해제)" else "본체까지 삭제",
            )
            absorbed += draft.title
        }
        return Outcome(absorbed = absorbed, kept = kept)
    }
}
