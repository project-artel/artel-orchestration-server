package kr.artel.orchestration.qa.verdict

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service

/**
 * 시나리오 실행 하나의 검증 판정을 코드로 다시 본다(ARTEL-1252).
 *
 * 따로 저장하지 않는다. 판정 근거 화면이 열릴 때마다 그 실행의 `PULSE` 를 처음부터 접는다 — 판정 한 건의 검사는
 * 0.2ms 안팎이고, 무거운 것은 `PULSE` 를 읽는 쿼리 하나다.
 */
@Service
class QaVerdictCodeCheckService(
    private val qaLogs: QaLogRepository,
    private val versions: TestCaseVersionRepository,
    private val objectMapper: ObjectMapper,
) {

    /**
     * [verdicts] 는 그 실행의 스텝 판정 행 전부다(같은 스텝을 다시 판정한 행 포함). 검증 판정만 결과를 낸다.
     * "앞 판정 시점" 은 바로 앞의 판정 행이다 — 입력 계기 효과가 그 사이에 바뀌었는지를 본다.
     *
     * [judgedBuildId] 는 TC 내용을 고르는 빌드다. 없으면 TC 의 `case_key` 를 쓴다.
     */
    suspend fun checkTry(qaTryId: Long, judgedBuildId: Long?, verdicts: List<Pair<QaLogEntity, JsonNode>>): Map<Long, CodeCheck> {
        val targets = verdicts.filter { (_, payload) -> payload.path("is_verification").asBoolean(false) }
        if (targets.isEmpty()) return emptyMap()
        val caseIds = targets.mapNotNull { (_, payload) -> caseIdOf(payload) }.distinct()
        val caseKeys = if (caseIds.isEmpty()) emptyMap()
        else versions.findCaseKeys(judgedBuildId ?: NO_BUILD, caseIds).toList().associate { it.testCaseId to it.caseKey }
        val targetIds = targets.map { requireNotNull(it.first.id) }.toSet()
        val pulses = qaLogs.findSdkPulses(qaTryId).toList()

        val now = PulseState()
        var before = PulseState()
        var cursor = 0
        val checks = linkedMapOf<Long, CodeCheck>()
        for ((row, payload) in verdicts.sortedBy { it.first.id }) {
            val verdictId = requireNotNull(row.id)
            while (cursor < pulses.size && requireNotNull(pulses[cursor].id) <= verdictId) {
                now.fold(objectMapper.readTree(pulses[cursor].payload.asString()))
                cursor++
            }
            if (verdictId in targetIds) {
                val caseKey = caseIdOf(payload)?.let(caseKeys::get)
                checks[verdictId] = if (caseKey.isNullOrBlank()) {
                    CodeCheck(CodeVerdict.UNKNOWN, emptyList())
                } else {
                    VerdictCodeChecker.check(CaseKeyEffects.parse(caseKey), now, before)
                }
            }
            before = now.copy()
        }
        return checks
    }

    private fun caseIdOf(payload: JsonNode): Long? = payload.path("case_id").takeIf { it.canConvertToLong() }?.asLong()

    private companion object {
        const val NO_BUILD = -1L
    }
}

/**
 * 사람이 다시 볼 판정인가(ARTEL-1252).
 *
 * - AI 가 통과라 했는데 코드가 통과로 확정하지 못했다: 잘못된 통과는 결함을 놓치므로, 코드가 확인한 통과만 믿는다.
 * - AI 가 실패라 했는데 코드는 통과로 확정했다: 자기모순 실패(69669) 같은 잘못된 실패를 잡는다.
 *
 * 코드가 확인하지 못한 실패는 넣지 않는다. 실패는 어차피 사람이 보는 결과다.
 */
fun needsReview(isVerification: Boolean, passed: Boolean, check: CodeCheck?): Boolean {
    if (!isVerification || check == null) return false
    return if (passed) check.result != CodeVerdict.PASS else check.result == CodeVerdict.PASS
}
