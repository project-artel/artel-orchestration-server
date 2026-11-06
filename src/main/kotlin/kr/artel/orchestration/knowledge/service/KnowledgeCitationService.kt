package kr.artel.orchestration.knowledge.service

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.knowledge.repository.KnowledgeUsageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 검색된 지식 중 **실제로 쓰인 것**을 가른다(ARTEL-293).
 *
 * V27이 검색으로 무엇이 나갔는지를 기록했지만, 그중 무엇이 행동에 반영됐는지는 아무도 몰랐다.
 * 셋을 갈라야 한다: **검색됨**(관측 가능, 이미 기록된다), **읽고 고려됨**(관측 불가이며 재려
 * 하지 않는다), **행동에 반영됨**(인용으로만 잡힌다). 이 서비스가 잡는 것은 세 번째다.
 *
 * ## 인용은 자기신고다
 *
 * `knowledge_event`(관측)와 성격이 다르다. 에이전트가 쓰고도 보고하지 않을 수 있어 **과소보고**
 * 방향으로 치우친다 — 안전한 방향이지만, 인용률로 모델을 줄 세울 때 "정직도" 차이가 섞인다.
 * 그렇다고 프롬프트로 인용을 압박하면 모델이 아무거나 인용하기 시작하고, 그때는 편향의 방향조차
 * 모르게 된다. 과소보고가 오염보다 낫다.
 *
 * ## 두 갱신의 실패 규칙이 다르다
 *
 * [recordCitations]는 실패를 **던진다**. 호출자(QA WS 라우터)가 이미 감사 로그를 쓸 줄 알고,
 * 무엇을 어떻게 무마할지는 WS 계약을 아는 쪽의 몫이다([KnowledgeSearchService]와 같은 규칙).
 *
 * [finalizeTry]/[finalizeRun]은 실패를 **삼킨다**. 호출자가 런 종료 처리 경로라 회복할 것이
 * 없고, 여기서 던지면 기록 하나가 런 종료 자체를 망가뜨린다 — 기록이 런을 죽이면 안 된다.
 */
@Service
class KnowledgeCitationService(
    private val usageRepository: KnowledgeUsageRepository,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(KnowledgeCitationService::class.java)

    /**
     * 스텝 판정이 보고한 인용을 기록한다.
     *
     * 매칭은 **런 스코프**다(`KnowledgeUsageRepository.markCited` 참조): 같은 런에서, 이 판정
     * 시점 이전에 검색된 그 항목의 행 전부. 스텝으로 묶지 않는 것이 요점이다 — 앞선 스텝에서
     * 검색한 것을 뒤 스텝에서 인용하는 것이 정상 동작이다.
     *
     * 인용 시각은 **호출 시점의 시계**로 찍는다. Agent가 프레임에 실어 보낸 timestamp를 쓰지
     * 않는 이유는, 그 값이 뒤로 밀려 있으면 방금 내보낸 검색 결과가 인용에서 빠지고 앞으로 밀려
     * 있으면 아직 안 난 검색까지 인용되기 때문이다. 시계는 검색 기록을 쓴 것과 같은 시계다.
     *
     * @return 알아본 id와 **거부한 id**. 거부는 조용히 버리지 않는다 — 환각 인용률 자체가 모델
     *   비교 지표라, 버리면 그 신호가 사라진다.
     */
    suspend fun recordCitations(qaTryId: Long, knowledgeIds: List<String>): CitationOutcome {
        if (knowledgeIds.isEmpty()) return CitationOutcome(emptyList(), emptyList())
        val citedAt = Instant.now(clock)

        val cited = mutableListOf<Long>()
        val rejected = mutableListOf<String>()
        // 같은 id를 두 번 인용해도 한 번으로 접는다. 두 번 세면 "인용한 항목 수"가 프레임의
        // 중복에 좌우된다 — 행은 어차피 첫 UPDATE에서 이미 true다.
        for (raw in knowledgeIds.distinct()) {
            val knowledgeId = raw.trim().toLongOrNull()
            if (knowledgeId == null) {
                rejected += raw
                continue
            }
            // 검색된 적 없는 id는 이 런에 행이 없어 0행이 갱신된다. 그것이 곧 환각 인용이다 —
            // 프로젝트나 스코프를 따로 검사할 필요가 없는 이유이기도 하다: 이 런이 실제로
            // 내보낸 행만 대상이므로, 남의 프로젝트 id를 인용해도 아무것도 표시되지 않는다.
            if (usageRepository.markCited(qaTryId, knowledgeId, citedAt) > 0) {
                cited += knowledgeId
            } else {
                rejected += raw
            }
        }
        return CitationOutcome(cited, rejected)
    }

    /**
     * 끝난 qa_try의 미인용 행을 `false`로 확정한다.
     *
     * **경계는 qa_try이지 WS 세션이 아니다.** 세션 하나가 런의 시나리오들을 순차 실행하고
     * qa_try는 시나리오당이라(ARTEL-259), 세션 종료에 걸면 앞선 시나리오들의 확정이 늦거나
     * 아예 누락된다.
     *
     * 두 번 불려도 안전하다(`cited IS NULL` 술어). 종료 경로가 겹치는 경우가 실제로 있다.
     */
    suspend fun finalizeTry(qaTryId: Long) {
        finalize("qa_try=$qaTryId") { usageRepository.finalizeUncitedByQaTryId(qaTryId) }
    }

    /** [finalizeTry]와 같되 런의 시나리오 try를 한꺼번에 확정한다(런 시작 실패·런 취소 경로). */
    suspend fun finalizeRun(qaRunId: Long) {
        finalize("qa_run=$qaRunId") { usageRepository.finalizeUncitedByQaRunId(qaRunId) }
    }

    /**
     * 확정 한 번. **실패는 삼키고 로그만 남긴다.**
     *
     * 이 시점에 런은 이미 끝났거나 끝나는 중이고, 호출자는 그 종료를 마무리하는 중이다. 여기서
     * 던지면 기록 하나 때문에 종료 처리가 깨진다 — 최악의 경우 소켓이 닫히고 런이 두 번 실패
     * 처리된다. 확정을 놓친 행은 NULL로 남고, 그것은 "모른다"라서 데이터를 망가뜨리지 않는다.
     *
     * `CancellationException`은 오류가 아니라 취소 신호라 반드시 다시 던진다.
     */
    private suspend fun finalize(scope: String, update: suspend () -> Int) {
        try {
            val finalized = update()
            if (finalized > 0) {
                logger.info("knowledge 인용 확정: {}, 미인용 {}행을 false로", scope, finalized)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("knowledge 인용 확정 실패: {} — {}", scope, error.message)
        }
    }
}

/**
 * 인용 보고 한 번의 결과.
 *
 * @property cited 이 런이 실제로 검색해 읽었고, 이제 인용으로 표시된 항목.
 * @property rejected 이 런에 기록이 없어 표시하지 못한 id. 숫자가 아니거나, 검색된 적이 없거나,
 *   판정 이후에야 검색된 것들이다. **개수 자체가 지표**이므로 호출자가 남긴다.
 */
data class CitationOutcome(
    val cited: List<Long>,
    val rejected: List<String>
)
