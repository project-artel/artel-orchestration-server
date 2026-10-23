package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service

/**
 * 앞 버전의 판정을 이번 버전 몫으로 이어받는다(ARTEL-1180).
 *
 * 새 버전 몫은 앞 버전에서 판정이 났으면 `REGRESSION` 으로 시작한다(ARTEL-994). 작은 패치마다 전부 다시 검증하길
 * 원하지 않는 사용자가 있어서, 그 판정을 이번 버전으로 가져올 수 있게 한다.
 *
 * **내용이 같은 몫만 이어받는다.** 지도 TC 는 같은 `case_key` 면 같고, 사용자 생성 TC 는 장면 · 스텝 · 사전조건 ·
 * 기대값이 같아야 한다. 내용이 바뀐 TC 의 판정을 가져오면 확인하지 않은 것을 확인한 것으로 보이게 된다.
 *
 * 이어받은 몫은 [TestCaseVersionEntity.verdictCarriedFromBuildId] 에 실제로 판정을 본 build 를 남긴다. 앞 버전도
 * 이어받은 것이었으면 그 출처를 그대로 넘긴다 — 판정을 실제로 본 곳이 하나로 남는다.
 *
 * 부르는 쪽이 lock 을 잡은 트랜잭션 안에서 부른다.
 */
@Service
class VerdictCarryOverService(
    private val versions: TestCaseVersionRepository,
    private val objectMapper: ObjectMapper,
) {

    /** 생성 요청이 고르는 방식. 주지 않으면 지금 규칙 그대로다(새 몫은 `REGRESSION`, 남는 몫은 판정 유지). */
    enum class Mode {
        /** 이어받지 않고, 이번 버전에 남아 있던 판정도 `REGRESSION` 으로 돌린다. 전부 다시 검증한다. */
        NONE,

        /** 내용이 같은 몫은 앞 버전 판정을 이어받는다. 이번 버전에 남아 있던 판정은 그대로다. */
        SAME_CONTENT,

        /** 고른 케이스만 이어받거나 판정을 지키고, 나머지 판정은 `REGRESSION` 으로 돌린다. */
        SELECTED,
    }

    enum class Skip {
        /** 이 버전에 그 케이스의 몫이 없다. */
        NOT_IN_VERSION,

        /** 이 버전 몫이 BROKEN 이다. */
        BROKEN,

        /** 이 버전에서 직접 판정이 났다. 앞 버전 것으로 덮지 않는다. */
        JUDGED_IN_THIS_VERSION,

        /** 가져올 버전에 그 케이스의 몫이 없거나, 판정(통과 · 실패)이 없다. */
        NO_PREVIOUS_VERDICT,

        /** 앞 버전과 내용이 다르다. */
        CONTENT_CHANGED,
    }

    data class Outcome(
        val carried: List<Long> = emptyList(),
        val reset: List<Long> = emptyList(),
        val skipped: Map<Long, Skip> = emptyMap(),
    )

    /** TC 생성 뒤의 처리. [selected] 는 [Mode.SELECTED] 일 때만 본다. */
    suspend fun afterGeneration(gameBuildId: Long, mode: Mode, selected: Set<Long> = emptySet()): Outcome {
        val rows = versions.findByGameBuildId(gameBuildId).toList()
        val previous = previousOf(rows, gameBuildId)
        val carried = mutableListOf<Long>()
        val reset = mutableListOf<Long>()
        for (row in rows) {
            val keep = when (mode) {
                Mode.NONE -> false
                Mode.SAME_CONTENT -> true
                Mode.SELECTED -> row.testCaseId in selected
            }
            if (keep) {
                if (eligibility(row, previous[row.testCaseId]) == null) {
                    versions.save(inherit(row, previous.getValue(row.testCaseId)))
                    carried += row.testCaseId
                }
            } else if (row.verificationStatus in VERDICTS) {
                versions.save(
                    row.copy(verificationStatus = REGRESSION, verifiedAt = null, verdictCarriedFromBuildId = null, verdictQaLogId = null),
                )
                reset += row.testCaseId
            }
        }
        sync(carried + reset)
        return Outcome(carried = carried, reset = reset)
    }

    /**
     * 생성 뒤에 판정을 가져온다. 이어받지 못한 케이스는 까닭과 함께 돌려준다.
     *
     * [fromBuildId] 를 주면 그 버전의 몫에서, 없으면 케이스마다 바로 앞 버전 몫에서 가져온다. [testCaseIds] 가 null 이면
     * 이 버전의 모든 몫이다(라이브러리 버전 메뉴의 "다른 버전에서 판정 결과 가져오기").
     */
    suspend fun carry(gameBuildId: Long, testCaseIds: Collection<Long>?, fromBuildId: Long? = null): Outcome {
        val here = versions.findByGameBuildId(gameBuildId).toList()
        val rows = here.filter { testCaseIds == null || it.testCaseId in testCaseIds }.associateBy { it.testCaseId }
        val previous = if (fromBuildId == null) {
            previousOf(rows.values, gameBuildId)
        } else {
            versions.findByGameBuildId(fromBuildId).toList().filter { it.testCaseId in rows }.associateBy { it.testCaseId }
        }
        val carried = mutableListOf<Long>()
        val skipped = linkedMapOf<Long, Skip>()
        for (testCaseId in (testCaseIds ?: rows.keys).distinct()) {
            val row = rows[testCaseId]
            val reason = if (row == null) Skip.NOT_IN_VERSION else eligibility(row, previous[testCaseId])
            if (reason != null) {
                skipped[testCaseId] = reason
                continue
            }
            versions.save(inherit(requireNotNull(row), previous.getValue(testCaseId)))
            carried += testCaseId
        }
        sync(carried)
        return Outcome(carried = carried, skipped = skipped)
    }

    /** 미리 보기 한 줄: 가져올 버전의 몫 하나와, 받는 버전에서의 상태와, 가져올 수 있는지. */
    data class PreviewRow(
        val source: TestCaseVersionEntity,
        val targetStatus: String?,
        val skip: Skip?,
    )

    /**
     * [fromBuildId] 버전의 판정 난 몫을 [gameBuildId] 버전에 가져오면 어떻게 되는지, 아무것도 바꾸지 않고 본다(ARTEL-1181).
     * 판정 가져오기 창의 오른쪽 표가 쓴다. 규칙은 [carry] 와 같다.
     */
    suspend fun preview(gameBuildId: Long, fromBuildId: Long): List<PreviewRow> {
        val targets = versions.findByGameBuildId(gameBuildId).toList().associateBy { it.testCaseId }
        return versions.findByGameBuildId(fromBuildId).toList()
            .filter { it.verificationStatus in VERDICTS }
            .sortedBy { it.testCaseId }
            .map { source ->
                val target = targets[source.testCaseId]
                PreviewRow(source, target?.verificationStatus, if (target == null) Skip.NOT_IN_VERSION else eligibility(target, source))
            }
    }

    /** 이어받을 수 있으면 null, 아니면 까닭. */
    private fun eligibility(row: TestCaseVersionEntity, previous: TestCaseVersionEntity?): Skip? = when {
        row.verificationStatus == BROKEN -> Skip.BROKEN
        row.verificationStatus in VERDICTS && row.verdictCarriedFromBuildId == null -> Skip.JUDGED_IN_THIS_VERSION
        previous == null || previous.verificationStatus !in VERDICTS -> Skip.NO_PREVIOUS_VERDICT
        !sameContent(row, previous) -> Skip.CONTENT_CHANGED
        else -> null
    }

    private fun inherit(row: TestCaseVersionEntity, previous: TestCaseVersionEntity) = row.copy(
        verificationStatus = previous.verificationStatus,
        verifiedAt = previous.verifiedAt,
        verdictCarriedFromBuildId = previous.verdictCarriedFromBuildId ?: previous.gameBuildId,
        // 근거도 판정을 따라온다. 이 판정을 실제로 낸 실행의 STATUS 행이다(ARTEL-1134).
        verdictQaLogId = previous.verdictQaLogId,
    )

    /**
     * 지도 몫은 `case_key` 가 같으면 같다 — 정체가 그 키로 묶였으므로 문구가 다듬어져도 같은 효과다. 키가 없는 옛 몫과
     * 사용자 생성 몫은 사람에게 보이는 네 칸으로 견준다.
     */
    private fun sameContent(row: TestCaseVersionEntity, previous: TestCaseVersionEntity): Boolean {
        val key = caseKeyOf(row)
        val previousKey = caseKeyOf(previous)
        if (key != null && previousKey != null) return key == previousKey
        return row.scene == previous.scene &&
            row.step.trim() == previous.step.trim() &&
            row.precondition.orEmpty().trim() == previous.precondition.orEmpty().trim() &&
            row.expectedValue.trim() == previous.expectedValue.trim()
    }

    private fun caseKeyOf(row: TestCaseVersionEntity): String? =
        runCatching { objectMapper.readTree(row.metadata.asString()) }.getOrNull()
            ?.path(CASE_KEY)?.asText(null)?.takeIf { it.isNotBlank() }

    private suspend fun previousOf(rows: Collection<TestCaseVersionEntity>, gameBuildId: Long): Map<Long, TestCaseVersionEntity> {
        val ids = rows.map { it.testCaseId }
        if (ids.isEmpty()) return emptyMap()
        return versions.findPreviousVersions(ids, gameBuildId).toList().associateBy { it.testCaseId }
    }

    private suspend fun sync(testCaseIds: List<Long>) {
        if (testCaseIds.isNotEmpty()) versions.syncIdentities(testCaseIds)
    }

    private companion object {
        const val CASE_KEY = "case_key"
        val REGRESSION = VerificationStatus.REGRESSION.name
        val BROKEN = VerificationStatus.BROKEN.name
        val VERDICTS = setOf(VerificationStatus.VERIFIED.name, VerificationStatus.FAILED.name)
    }
}
