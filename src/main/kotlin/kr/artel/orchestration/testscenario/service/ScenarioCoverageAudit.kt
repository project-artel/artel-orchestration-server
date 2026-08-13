package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.ScenarioResult

/**
 * 저작 결과가 "전량을 보고 만든 것"인지 기계로 검사한다(2단계).
 *
 * **왜 기계가 세는가.** "모든 TC를 검토했나"를 Agent에게 물으면 다 봤다고 답한다 — 자기 평가를
 * 근거로 삼는 순환이다(`spec.status`를 저작에 쓰지 않기로 한 것과 같은 이유). 그래서 묻지 않고
 * **판정을 내놓게 한 뒤 그 판정을 센다.** 판단(무엇이 관련 있는가)은 LLM이, 계수(빠짐없이
 * 판정·배치됐는가)는 여기가 한다.
 *
 * Agent가 포함 목록만 돌려주면 이 검사가 성립하지 않는다. 나머지를 *검토하고 뺀 것*인지
 * *아예 안 본 것*인지 구분할 수단이 없어 검사할 대상 자체가 없기 때문이다. 그래서 계약이
 * [ReviewedCases]처럼 **전 건 판정**이다.
 *
 * DB를 모르는 순수 함수로 둔 것은 이 규칙이 저장 절차와 따로 검증돼야 하기 때문이다 —
 * 규칙이 틀리면 통합 테스트는 "저장이 됐다/안 됐다"만 알려주고 어느 규칙이 틀렸는지는 안 알려준다.
 */
object ScenarioCoverageAudit {

    /**
     * @property unreviewed 판정이 아예 없는 케이스. **검토하지 않았다는 뜻**이다(1차 검수).
     * @property missing 관련 있다고 판정해 놓고 어떤 스텝도 담지 않은 케이스(2차 검수 ①).
     * @property ghost 이 프로젝트에 없는 `case_id`. 지어낸 번호다(2차 검수 ③).
     * @property excess 판정에 없었는데 스텝이 담은 케이스(2차 검수 ②). 자연어 요청에서는 **거부하지
     *   않는다** — 선언은 Agent 자신의 추정이라 절대 기준으로 삼으면 1패스에서 좁게 잡은 실수를
     *   2패스가 고칠 길이 사라진다. 집합의 출처가 사람인 모드(후속)에서만 거부 대상이 된다.
     */
    data class Findings(
        val unreviewed: List<Long> = emptyList(),
        val missing: List<Long> = emptyList(),
        val ghost: List<Long> = emptyList(),
        val excess: List<Long> = emptyList(),
    ) {
        /** 저장을 막아야 하는가. 초과는 여기 들어가지 않는다(위 참조). */
        val rejected: Boolean get() = unreviewed.isNotEmpty() || missing.isNotEmpty() || ghost.isNotEmpty()

        /** 사용자에게 돌려줄 문구. 시나리오가 왜 저장되지 않았는지 이것 말고는 알 길이 없다. */
        fun rejectionMessage(): String = buildList {
            if (unreviewed.isNotEmpty()) add("${unreviewed.size}건을 검토하지 않았습니다")
            if (missing.isNotEmpty()) add("관련 있다고 판단한 ${missing.size}건이 시나리오에 빠졌습니다")
            if (ghost.isNotEmpty()) add("존재하지 않는 케이스 ${ghost.size}건을 가리켰습니다")
        }.joinToString(", ") + ". 저장하지 않았습니다."

        /** 사람이 읽을 한 줄. 무엇이 몇 건 어긋났는지만 말한다 — id는 로그와 재시도 프레임이 싣는다. */
        fun summary(): String = buildList {
            if (unreviewed.isNotEmpty()) add("검토 안 함 ${unreviewed.size}건")
            if (missing.isNotEmpty()) add("판정했으나 안 담음 ${missing.size}건")
            if (ghost.isNotEmpty()) add("없는 케이스 번호 ${ghost.size}건")
            if (excess.isNotEmpty()) add("판정 밖 ${excess.size}건(기록만)")
        }.joinToString(" · ").ifEmpty { "이상 없음" }
    }

    /**
     * @param projectCaseIds 이 프로젝트의 TestCase id 전량. 1차 검수의 기준이자 유령 판별의 기준이다.
     * @param reviewed Agent의 전 건 판정. **null이면 검사를 통째로 건너뛴다** — 이 필드를 보내지 않는
     *   구버전 Agent와 함께 배포되기 위해서다. 검사를 켜고 끄는 스위치가 이 null 하나뿐이라
     *   롤백이 Agent 재배포만으로 끝난다.
     */
    fun audit(
        projectCaseIds: Set<Long>,
        reviewed: ReviewedCases?,
        scenarios: List<ScenarioResult>,
    ): Findings {
        if (reviewed == null) return Findings()

        val declared = reviewed.included.toSet()
        val judged = declared + reviewed.excluded.toSet()
        val used = scenarios.flatMap { scenario -> scenario.steps.mapNotNull { it.caseId } }.toSet()

        return Findings(
            // 판정이 프로젝트 전량을 덮지 못한 부분. 있는 케이스만 따진다 — 판정에 남의 프로젝트
            // 번호가 섞여 있어도 그건 "검토 누락"이 아니라 유령이고, 그쪽은 담겼을 때만 문제 삼는다.
            unreviewed = (projectCaseIds - judged).sorted(),
            missing = (declared - used).sorted(),
            ghost = (used - projectCaseIds).sorted(),
            excess = (used - declared).sorted(),
        )
    }
}
