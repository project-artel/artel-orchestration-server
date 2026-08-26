package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.repository.CapabilityEvidenceRepository
import org.springframework.stereotype.Service

/**
 * 기능 하나의 **사전조건**을 읽는 유일한 자리(ARTEL-533).
 *
 * 저작 곳곳이 `capability.given_text` 를 직접 읽고 있었는데, **적재기는 그 칸을 채우지 않는다.**
 * 실측(`wv-editor-latest.json` 실적재): 기능 491건 중 `given_text` 0건, `condition_tree` 491건.
 * 손으로 넣은 골든 지도(기능 18)만 `given_text` 를 들고 있었고, 그래서 저작 QA 가 지금까지 실제
 * 지도에서는 무동작인 코드를 계속 통과시켜 왔다.
 *
 * 읽는 자리를 하나로 모으는 이유는 그 사고가 반복되지 않게 하기 위해서다 — 새 소비처가 `given_text`
 * 를 다시 직접 읽으면 같은 함정에 다시 빠진다.
 */
@Service
class ScenarioConditionReader(
    private val objectMapper: ObjectMapper,
    private val evidenceRepository: CapabilityEvidenceRepository,
) {

    /**
     * @property guards 코드가 판단에 쓰는 비교들. **주어를 못 찾은 비교는 여기 없다.**
     * @property text 사용자에게 보여 줄 한 줄. 판단에 쓰지 않으므로 더 너그럽게 담는다.
     */
    data class Condition(val guards: List<Guard>, val text: String?) {

        /**
         * 지금 아는 값과 어긋나는 첫 비교. 없으면 `null`.
         *
         * **값을 모르는 변수는 어긋난다고 보지 않는다** — 경로 계산 전체를 관통하는 규칙이다.
         * 대부분의 기능이 `InteractionLock.IsLocked == 0` 을 요구하는데 그 값을 아는 경우는 드물고,
         * 모르는 것을 위반으로 세면 거의 모든 길이 막힌다.
         */
        fun violatedIn(state: Map<String, String>): Guard? = guards.firstOrNull { guard ->
            val have = state[guard.variable]
            have != null && !guard.holds(have)
        }

        companion object {
            val NONE = Condition(emptyList(), null)
        }
    }

    /**
     * 트리가 있으면 **트리만 믿는다.**
     *
     * 둘 다 있는 지도에서 합치면 어느 쪽이 최신인지 알 수 없고, 트리가 원본이라고 스키마가 이미
     * 말하고 있다(`CapabilityEntity.givenText` — "트리 원본은 `CapabilityEvidenceEntity.conditionTree`").
     * 트리가 없는 지도(손적재·구 문서)는 예전처럼 문자열을 읽는다.
     */
    suspend fun of(capability: CapabilityEntity): Condition {
        val tree = capability.id
            ?.let { evidenceRepository.findById(it) }
            ?.conditionTree
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.hasNonNull("kind") }
            ?: return Condition(
                ScenarioStateReader.comparisonsIn(capability.givenText),
                ScenarioStateReader.conditionText(capability.givenText),
            )
        return Condition(ScenarioConditionTree.guards(tree), ScenarioConditionTree.text(tree))
    }
}
