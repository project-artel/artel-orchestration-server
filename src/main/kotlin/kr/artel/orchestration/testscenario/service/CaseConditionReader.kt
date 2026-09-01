package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import org.springframework.stereotype.Component

/**
 * 케이스의 **전제를 읽는 유일한 창구**(ARTEL-627).
 *
 * 앞서 다섯 곳이 각자 `precondition` 문자열을 [ScenarioStateReader.guardsOf] 로 파싱했다. 문장은
 * 사람에게 보여주려고 다듬은 것이라 되읽을 때 잃는 것이 있고 — 대상의 주인, 갈래, 식 — 그 손실을
 * 다섯 곳이 각자 다른 방법으로 메우기 시작했다. 그게 이 개편이 없애려는 것이다.
 *
 * 그래서 읽는 곳을 하나로 못 박는다. `v_content_map_capability` 가 TC 생성기의 유일한 창구인 것과
 * 같은 이유다 — 여러 곳이 각자 읽으면 **각자 다르게 관대해진다.**
 *
 * ## 되짚을 것이 없으면 문장으로 물러선다
 *
 * 구버전 엑셀 경로로 들어온 행에는 트리가 없다. 그 줄까지 못 읽겠다고 하면 저작이 통째로 멎으므로,
 * 그때는 예전처럼 문장을 읽는다. **새로 들어오는 줄은 전부 트리를 든다** — 그것을
 * `MapTestCaseWriterGoldenTest` 가 못 박고 있다.
 */
@Component
class CaseConditionReader(private val objectMapper: ObjectMapper) {

    private val parser = EvidenceParser(objectMapper)

    /** 이 케이스의 전제 구조. 트리가 없으면 null 이고, 그때 부르는 쪽은 문장으로 물러선다. */
    fun conditionOf(case: TestCaseEntity): ConditionNode? = case.condition
        ?.let { runCatching { parser.parseCondition(objectMapper.readTree(it.asString())) }.getOrNull() }

    /** 이 케이스가 요구하는 비교들. 트리가 있으면 구조에서, 없으면 문장에서. */
    fun guardsOf(case: TestCaseEntity): List<Guard> =
        conditionOf(case)?.let(ScenarioStateReader::guardsIn)
            ?: ScenarioStateReader.guardsOf(case.precondition)

    /** 이 케이스가 **확정하는** 값(`==` 만). */
    fun knownValuesOf(case: TestCaseEntity): Map<String, String> =
        conditionOf(case)?.let(ScenarioStateReader::knownValuesIn)
            ?: ScenarioStateReader.knownValuesOf(case.precondition)
}
