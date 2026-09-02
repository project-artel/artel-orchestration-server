package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 지도가 낸 케이스를 `test_case` 에 앉힌다(ARTEL-578).
 *
 * [MapTestCaseGenerator] 는 만들기만 하고 아무도 저장하지 않았다. 그래서 저작은 여전히 구버전
 * `specs_v2` 가 엑셀 경로로 넣은 줄을 읽었고, `capability_key` 칸(ARTEL-553)도 그 키로 지도를 되짚는
 * 길(ARTEL-555)도 **한 번도 안 쓰였다** — 오늘 쓰이는 모든 행의 그 칸이 `NULL` 이다. 여기가 그 자리다.
 *
 * ## 이 경로의 행만 건드린다
 *
 * `metadata.origin` 에 [ORIGIN] 을 적는다. 손으로 쓴 케이스와 엑셀로 들어온 케이스는 그 표시가 없고,
 * 갈아 끼울 때 건너뛴다. 표시가 없으면 남의 행을 지우게 된다 — 두 경로가 한동안 공존하는 것이
 * 되돌아갈 길이므로(ARTEL-556 의 대조가 통과하기 전까지) 그 경계가 곧 안전장치다.
 *
 * ## 같은 케이스란 무엇인가
 *
 * **사용자에게 보이는 것이 같으면 같은 케이스다** — 씬 · 사전조건 · 행동 · 기대결과. `capability_key`
 * 하나로는 안 된다. 기능 하나가 확인할 수 있는 효과마다 줄을 내므로 키가 여러 줄에 걸린다.
 *
 * 반대로 서로 다른 기능이 똑같은 네 칸을 내면 한 줄로 접는다. 사람 눈에 같은 시험이 표에 두 번
 * 나오는 것이 더 나쁘다. 접힌 줄의 `capability_key` 는 먼저 온 기능의 것이고, 그만큼 되짚기가 한쪽만
 * 가리킨다 — 실측에서 이 자리에 몇 건이 걸리는지는 테스트가 센다.
 *
 * ## 사라진 기능은 지우되, 시나리오가 든 것은 지우지 않는다
 *
 * 시나리오는 스텝 안에 `case_id` 를 숫자로 들고 있다(`test_scenario.steps` JSONB). 그 줄을 지우면
 * 시나리오에 **가리키는 것이 없는 번호**가 남고, 아무도 그 사실을 모른다. 그래서 아직 인용된 줄은
 * 지우지 않고 `BROKEN` 으로 돌린다 — "코드가 바뀌어 더는 성립하지 않는다"가 정확히 그 뜻이고,
 * 저작 화면이 그 표시를 읽어 시나리오가 상한 것을 사람에게 보인다.
 */
@Service
class MapTestCaseWriter(
    private val generator: MapTestCaseGenerator,
    private val contentMaps: ContentMapRepository,
    private val testCases: TestCaseRepository,
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(MapTestCaseWriter::class.java)

    /**
     * 이 지도의 케이스를 다시 앉힌다. 적재 트랜잭션 안에서 불린다 — 지도가 바뀌면 케이스도 함께
     * 바뀌어야 하고, 한쪽만 커밋되면 표가 서로 다른 시점을 가리킨다.
     */
    suspend fun rewrite(contentMapId: Long): Result {
        val projectId = contentMaps.findProjectId(contentMapId)
            ?: return Result().also { logger.warn("지도 {} 의 프로젝트를 찾지 못해 케이스를 앉히지 않는다", contentMapId) }

        val generated = generator.generate(contentMapId).distinctBy(::identityOf)
        val mine = testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter(::isMine)
        val existing = mine.associateBy(::identityOf)
        // **키로 못 찾은 줄은 보이는 네 칸으로 한 번 더 찾는다**(ARTEL-617). 이 판이 오기 전에 앉은
        // 행에는 키가 없고, 키를 못 알아본 줄을 새 줄로 치면 같은 케이스가 두 벌이 된다.
        val byText = mine.groupBy(::visibleIdentityOf)
        val claimed = mutableSetOf<Long>()
        val cited = testCases.findCaseIdsCitedByScenarios(projectId).toList().toSet()

        var created = 0
        var updated = 0
        for (case in generated) {
            val prior = existing[identityOf(case)]
                ?: byText[visibleIdentityOf(case)]?.firstOrNull { it.id !in claimed }
            prior?.id?.let(claimed::add)
            if (prior == null) {
                testCases.save(entityOf(projectId, contentMapId, case))
                created++
                continue
            }
            // **보이는 칸까지 되돌린다**(ARTEL-617). 정체가 문장에서 지도로 옮겨 갔으므로 같은
            // 줄이어도 문구가 다를 수 있고, 되돌리지 않으면 문장 규칙을 고친 보람이 기존 행에
            // 영영 닿지 않는다. 이 행들은 지도가 낸 것이라 지도가 원본이다.
            val next = prior.copy(
                scene = case.scene,
                step = case.step,
                precondition = case.precondition,
                condition = conditionJson(case),
                expectedValue = case.expected,
                expectedItems = expectedItemsJson(case),
                status = case.status,
                capabilityKey = case.capabilityKey,
                metadata = metadataOf(contentMapId, case),
                // 상했다고 돌려놓았던 줄이 지도에 다시 나타나면 성립한다는 뜻이다.
                verificationStatus = if (prior.verificationStatus == BROKEN) DRAFT else prior.verificationStatus,
            )
            // **`Json` 은 값으로 견줄 수 없다.** 데이터 클래스의 `!=` 에 맡기면 내용이 같아도 늘
            // 다르다고 나와, 아무것도 안 바뀐 재적재가 매번 전량을 다시 쓴다 — 실측에서 두 번째·세
            // 번째 적재가 계속 `updated=49` 였다. 쓰기가 헛도는 것보다 나쁜 것은 그 수가 "49행이
            // 달라졌다"고 거짓말하는 것이다.
            if (changed(prior, next)) {
                testCases.save(next)
                updated++
            }
        }

        // 아무 케이스도 자기 줄이라 하지 않은 것이 사라진 것이다. 키로도 문장으로도 안 잡혔다는 뜻이다.
        var deleted = 0
        var broken = 0
        for (stale in mine.filterNot { it.id in claimed }) {
            if (stale.id in cited) {
                if (stale.verificationStatus != BROKEN) {
                    testCases.save(stale.copy(verificationStatus = BROKEN))
                    broken++
                }
            } else {
                testCases.delete(stale)
                deleted++
            }
        }

        return Result(created = created, updated = updated, deleted = deleted, broken = broken)
    }

    /**
     * 이 줄이 실제로 달라졌나.
     *
     * **`Json` 은 값으로 견줄 수 없다.** 같은 내용이어도 다른 객체면 다르다고 나오고, **글자로
     * 견줘도 안 된다** — `jsonb` 는 키 순서를 정규화해서 저장하므로 읽어온 글자가 쓴 글자와
     * 다르다. 파싱해서 트리로 견준다.
     *
     * 이것을 안 하면 아무것도 안 바뀐 재적재가 매번 전량을 다시 쓴다. 헛도는 쓰기보다 나쁜 것은
     * 그 수가 "49행이 달라졌다"고 거짓말하는 것이다 — 실측에서 세 번을 이어 적재해도 계속
     * `updated=49` 였다.
     *
     * 여기 실린 JSONB 칸이 셋(`metadata` · `condition` · `expected_items`)이라, 셋 다 빼고
     * 나머지를 견준 뒤 각각 따로 본다.
     */
    private fun changed(prior: TestCaseEntity, next: TestCaseEntity): Boolean =
        prior.copy(
            metadata = next.metadata,
            condition = next.condition,
            expectedItems = next.expectedItems,
        ) != next ||
            !sameJson(prior.metadata, next.metadata) ||
            !sameJson(prior.condition, next.condition) ||
            !sameJson(prior.expectedItems, next.expectedItems)

    private fun sameJson(a: Json?, b: Json?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return runCatching {
            objectMapper.readTree(a.asString()) == objectMapper.readTree(b.asString())
        }.getOrDefault(false)
    }

    /**
     * **문장이 아니라 지도가 정하는 값으로 정체를 잡는다**(ARTEL-617).
     *
     * 앞서 정체는 사용자에게 보이는 네 칸이었다. 그러면 **문장 규칙을 고칠 때마다 정체가 바뀌고**,
     * 옛 줄을 인용한 시나리오가 통째로 상한다 — 실측에서 하루에 규칙을 다섯 번 고치자 케이스
     * 18건과 시나리오 3개가 `BROKEN` 이 됐다. 문구가 좋아지는 것이 사용자의 저작물을 깨뜨릴
     * 이유는 없다.
     */
    private fun identityOf(case: MapTestCase): String = case.identity

    private fun identityOf(row: TestCaseEntity): String = keyOf(row) ?: visibleIdentityOf(row)

    private fun keyOf(row: TestCaseEntity): String? =
        runCatching { objectMapper.readTree(row.metadata.asString()) }.getOrNull()
            ?.path(CASE_KEY)?.asText(null)?.takeIf { it.isNotBlank() }

    /**
     * 사용자에게 보이는 네 칸. **키가 생기기 전에 앉은 줄을 찾을 때만** 쓴다 — 그때는 다른 잣대가
     * 없다. 새 줄끼리는 지도 키로 견준다.
     */
    private fun visibleIdentityOf(case: MapTestCase): String =
        listOf(case.scene, case.precondition, case.step, case.expected).joinToString(" ")

    private fun visibleIdentityOf(row: TestCaseEntity): String =
        listOf(row.scene, row.precondition.orEmpty(), row.step, row.expectedValue).joinToString(" ")

    private fun isMine(row: TestCaseEntity): Boolean =
        runCatching { objectMapper.readTree(row.metadata.asString()) }.getOrNull()
            ?.path(ORIGIN_FIELD)?.asText() == ORIGIN

    /**
     * 사전조건의 **구조**를 그대로 싣는다(ARTEL-627).
     *
     * 지도 조회 API 가 쓰는 표현([ConditionNodeResponse])을 그대로 쓴다. 표현을 두 벌 만들면 같은
     * 트리가 두 모양으로 나가고, 읽는 쪽이 어느 쪽인지 물어야 한다.
     *
     * 트리가 없으면 null 이다 — 조건이 없는 것과 모르는 것은 다르고, 빈 객체로 적으면 저작이
     * "아무 전제도 없는 케이스"로 읽는다.
     */
    private fun conditionJson(case: MapTestCase): Json? = case.condition
        ?.let { objectMapper.writeValueAsString(ConditionNodeResponse.of(it)) }
        ?.let(Json::of)

    private fun entityOf(projectId: Long, contentMapId: Long, case: MapTestCase) = TestCaseEntity(
        projectId = projectId,
        scene = case.scene,
        step = case.step,
        precondition = case.precondition,
        condition = conditionJson(case),
        expectedValue = case.expected,
        expectedItems = expectedItemsJson(case),
        status = case.status,
        capabilityKey = case.capabilityKey,
        metadata = metadataOf(contentMapId, case),
    )

    /**
     * 기대결과 항목들을 그대로 싣는다(V81). 목록이 비면 null 이다 — 지도를 못 되짚는 행과 같은
     * 값이어야 하고, 빈 배열은 "기대결과가 없다"는 다른 말이다.
     */
    private fun expectedItemsJson(case: MapTestCase): Json? = case.expectedItems
        .takeIf { it.isNotEmpty() }
        ?.let { objectMapper.writeValueAsString(it) }
        ?.let(Json::of)

    /**
     * 출처와, 이 줄이 어느 지도에서 왔는지와, 등급이 그렇게 나온 이유를 싣는다.
     *
     * `test_case` 에는 `content_map_id` 칸이 없다(ARTEL-553 이 키만 냈다). 지도가 여럿인 프로젝트에서
     * 어느 지도의 산물인지 알아야 갈아 끼울 범위를 좁힐 수 있으므로 여기 적어 둔다.
     */
    private fun metadataOf(contentMapId: Long, case: MapTestCase): Json = Json.of(
        objectMapper.writeValueAsString(
            buildMap {
                put(ORIGIN_FIELD, ORIGIN)
                put("content_map_id", contentMapId)
                put("gaps", case.gaps)
                // 문장과 무관한 정체(ARTEL-617). 다음 적재가 이것으로 같은 줄을 찾는다.
                put(CASE_KEY, case.identity)
                // **어느 화면이 되나**(ARTEL-614). 저작이 브리지를 고를 때 읽는다 — 기대결과
                // 산문에서 뽑으면 그것이 곧 없애려는 문자열 맞춤이다.
                case.arrivesAt?.let { put(ARRIVES_AT, it) }
            }
        )
    )

    data class Result(
        val created: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0,
        val broken: Int = 0,
    )

    companion object {
        /** 이 경로가 앉힌 행이라는 표시. 이 값이 없는 행은 남의 것이라 건드리지 않는다. */
        const val ORIGIN = "content-map"
        const val ORIGIN_FIELD = "origin"

        /** 이 케이스를 실행하면 되는 화면. 씬 전환이 아니면 없다. */
        const val ARRIVES_AT = "arrives_at"

        /** 문장과 무관한 정체(ARTEL-617). 지도 키와 그 케이스를 낸 효과로 만든다. */
        const val CASE_KEY = "case_key"
        private const val BROKEN = "BROKEN"
        private const val DRAFT = "DRAFT"
    }
}
