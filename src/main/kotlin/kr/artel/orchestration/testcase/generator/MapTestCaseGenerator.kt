package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import org.springframework.stereotype.Service

/**
 * 지도에서 케이스를 뽑는다(ARTEL-554).
 *
 * ## 읽는 곳은 하나다
 *
 * `v_content_map_capability` — 뷰가 자기 주석에 **"TC 생성기가 읽는 유일한 창구"** 라고 적어 두었고,
 * 그 이유도 함께 적혀 있다:
 *
 * > 읽는 곳을 한 군데로 못 박지 않으면 TC 생성기가 근거 문서를 직접 보게 되고, 그 순간
 * > "TC 입력은 content_map 단독"이라는 계약이 무너진다
 *
 * 그래서 여기는 근거 문서를 열지 않는다. 뷰가 이미 `not-a-step` 과 `merged_into` 를 걸러 낸다 —
 * 실측(적재기가 앉힌 word-venture 지도)에서 기능 491행이 뷰를 지나면 **51행**이 된다.
 *
 * ## 무엇이 케이스가 되나
 *
 * **확인할 것이 있어야 케이스다.** `then` 에 쓸 수 있는 효과(`observable` · `availability`)가 하나도
 * 없으면 내지 않는다. 실행해도 통과·실패를 말할 수 없는 줄이라, 사용자에게는 기대결과가 빈 케이스로
 * 보인다.
 *
 * 실측으로 그 자리가 51행 중 15행이고, 그중 12행은 `record_kind = 'flow'` 다 —
 * **명세 후보가 아니라 연결점**이라고 문서가 스스로 말한 레코드다(`RecordKind` KDoc). 나머지 3행은
 * `state` 효과만 든다(값은 바뀌는데 화면에서 볼 수 없다).
 *
 * 버리는 것이 아니다. `v_spec_gap` 이 그 자리를 `then-missing` 으로 이미 세고 있고, 그것은
 * **"QA 결함이 아니라 개발 우선순위 신호"** 로 화면에 나간다.
 *
 * ## 순서
 *
 * 뷰의 `ORDER BY scene_name, capability_id` 를 그대로 물려받는다. 그 정렬은 취향이 아니라
 * 프롬프트 캐시 계약이다([ContentMapRepository.findCapabilityRows] 의 주석).
 */
@Service
class MapTestCaseGenerator(
    private val contentMaps: ContentMapRepository,
    private val effects: CapabilityEffectRepository,
    private val objectMapper: ObjectMapper,
) {

    suspend fun generate(contentMapId: Long): List<MapTestCase> =
        contentMaps.findCapabilityRows(contentMapId).toList().flatMap { row -> casesOf(row) }

    /**
     * 기능 하나에서 케이스 **여럿**이 나온다 — 확인할 수 있는 효과 하나마다 하나다.
     *
     * 합쳐서 한 줄로 내면 실행하는 사람이 무엇을 볼지 모른다([MapTestCasePhrasing.expectedEach] 의
     * 주석에 실측이 있다). 구버전도 같은 기능에서 아홉 줄을 냈다.
     */
    private suspend fun casesOf(row: ContentMapCapabilityRow): List<MapTestCase> {
        // 키가 없는 행은 evidence 출신이 아니다. 케이스가 지도를 되짚을 방법이 없으므로 내지 않는다 —
        // 되짚지 못하는 케이스는 이 개편이 없애려는 바로 그 문자열 맞춤으로 돌아간다.
        val key = row.capabilityKey ?: return emptyList()
        val effectRows = effects.findByCapabilityIdOrderByIdAsc(row.capabilityId).toList()
        val outcomes = MapTestCasePhrasing.expectedEach(effectRows)
        if (outcomes.isEmpty()) return emptyList()

        val condition = conditionOf(row)
        val precondition = MapTestCasePhrasing.precondition(row.sceneName, condition)
        val step = MapTestCasePhrasing.step(row.interaction, row.inputKey, row.controlLabel, row.controlPath)
        val gaps = gapsOf(row)
        return outcomes.map { outcome ->
            MapTestCase(
                capabilityKey = key,
                scene = row.sceneName,
                precondition = precondition,
                step = step,
                expected = outcome,
                status = row.status,
                gaps = gaps,
            )
        }
    }

    /**
     * 저장된 조건 트리를 읽는다. **파서가 읽는다** — 대문자 `kind` 도 이름표 없는 노드도 그쪽이
     * 이미 다룬다. 여기서 한 벌 더 쓰면 두 곳이 서로 다르게 관대해진다.
     */
    private fun conditionOf(row: ContentMapCapabilityRow): ConditionNode? {
        val json = row.conditionTree
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isObject && !it.isEmpty }
            ?: return null
        return EvidenceParser(objectMapper).parseCondition(json)
    }

    private fun gapsOf(row: ContentMapCapabilityRow): List<String> =
        row.gaps?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.asText(null) }
            .orEmpty()
}
