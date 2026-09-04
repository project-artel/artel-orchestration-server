package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 조작의 **전제를 읽는 유일한 창구**.
 *
 * [CaseConditionReader] 가 케이스에 대해 하는 일을 `capability` 에 대해 한다. 읽는 곳을 하나로
 * 못 박는 이유도 같다 — 여러 곳이 각자 읽으면 각자 다르게 관대해진다.
 *
 * ## 왜 `given_text` 를 안 읽나
 *
 * `capability.given_text` 는 명세의 `given` 을 사람 말로 옮기는 칸인데 **419 행 전부 `null`**
 * 이다. 문장 생성은 ARTEL-447 몫이고 아직 미착수다. 적재기가 비워 두는 이유도 적혀 있다 —
 * *"없는 값을 지어내면 조건으로 갈리는 간선의 설명이 거짓이 된다"*.
 *
 * 그런데 [ScenarioPathService] 가 그 칸을 읽었다. 비교를 하나도 못 찾으니
 * [ScenarioStateReader.violated] 가 늘 `null`(위반 없음)을 돌려주고, **길찾기가 조건을 통째로
 * 안 보고 있었다.** 지금 상태에서 못 하는 조작을 집는 일, 올리라는 요구에 내리는 조작을 집는
 * 일이 거기서 나온다.
 *
 * 조건은 `capability_evidence.condition_tree` 에 419 / 419 있다. 문장을 되읽는 것이 아니라
 * 구조를 그대로 읽는다 — 문장 되읽기는 V78 에서 이미 걷어낸 길이다.
 *
 * ## 지도 하나치를 한 번에 읽고, 읽어 둔 것을 다시 쓴다
 *
 * `capability` 마다 조회하면 안 된다. 케이스 두 개씩의 행렬이 케이스 수의 제곱만큼 이 판단을 부른다
 * (케이스 83 건이면 6,806 칸). 지도 하나치는 419 행이라 한 번에 들고 도는 편이 싸다.
 *
 * **한 번에 읽는 것만으로는 모자랐다.** [ScenarioPathService] 가 요구마다 `writerFor` 를 부르고
 * 그 안에서 다시 `sceneHop` 을 부른다. 자리마다 이것을 새로 부르면 419 행을 읽고 JSON 114KB 를
 * 파싱하는 일이 한 요청에서 열 번도 넘게 돈다. 실측 저작에서 `find_path` 가 20 초 안에 답을 못
 * 했고, 모델이 *"route lookup timed out"* 이라며 시나리오를 한 건도 안 썼다.
 *
 * 그래서 지도별로 한 벌을 들고 있다가 다시 쓴다. **언제까지 쓸 수 있는지는 시간이 아니라 지도가
 * 정한다** — `content_map.updated_at` 이 움직이면 적재기가 갈아 끼운 것이므로 다시 읽는다.
 * 시간으로 끊으면 그 초가 왜 그 값인지 아무도 답할 수 없고, 재적재 직후의 답이 틀린 채로 남는다.
 */
@Component
class CapabilityConditionReader(
    private val contentMaps: ContentMapRepository,
    private val objectMapper: ObjectMapper,
) {
    private val parser = EvidenceParser(objectMapper)

    /** 지도별로 읽어 둔 한 벌과, 그것을 읽을 때 지도가 마지막으로 앉은 때. */
    private val held = ConcurrentHashMap<Long, Held>()

    private data class Held(val at: String?, val conditions: Map<Long, ConditionNode>)

    /** 이 지도의 `capabilityId` → 조건 트리. 트리가 없는 행은 담지 않는다. */
    suspend fun of(contentMapId: Long): Map<Long, ConditionNode> {
        val at = contentMaps.findUpdatedAt(contentMapId)
        held[contentMapId]?.takeIf { it.at == at }?.let { return it.conditions }
        val read = read(contentMapId)
        held[contentMapId] = Held(at, read)
        return read
    }

    private suspend fun read(contentMapId: Long): Map<Long, ConditionNode> =
        contentMaps.findAllCapabilityRows(contentMapId).toList()
            .mapNotNull { row ->
                val tree = row.conditionTree
                    ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
                    ?.takeIf { it.isObject && !it.isEmpty }
                    ?: return@mapNotNull null
                runCatching { parser.parseCondition(tree) }.getOrNull()
                    ?.let { row.capabilityId to it }
            }
            .toMap()
}
