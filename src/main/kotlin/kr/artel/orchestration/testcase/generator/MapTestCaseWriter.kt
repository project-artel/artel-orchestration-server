package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service

/**
 * 지도가 낸 케이스를 `test_case` 에 앉힌다(ARTEL-578).
 *
 * [MapTestCaseGenerator] 는 만들기만 하고 아무도 저장하지 않았다. 그래서 저작은 여전히 구버전
 * `specs_v2` 가 엑셀 경로로 넣은 줄을 읽었고, `capability_key` 칸(ARTEL-553)도 그 키로 지도를 되짚는
 * 길(ARTEL-555)도 **한 번도 안 쓰였다** — 오늘 쓰이는 모든 행의 그 칸이 `NULL` 이다. 여기가 그 자리다.
 *
 * ## 정체는 하나, 버전마다 몫 하나 (ARTEL-1018)
 *
 * `metadata.origin` 에 [ORIGIN] 을 적는다. 손으로 쓴 케이스와 엑셀로 들어온 케이스는 origin 표시가 없고,
 * 이 writer 가 건드리지 않는다.
 *
 * 같은 케이스(`metadata.case_key`)는 프로젝트 안에서 `test_case` 한 행이다. build 마다 달라지는 내용과
 * 판정은 `test_case_version` 에 남는다. 예전(ARTEL-971)에는 build 마다 행이 따로 있어, 시나리오 · 폴더 ·
 * 판정이 버전마다 다른 id 를 가리켰다. 이 writer 는 한 build 의 버전 몫만 만들고 고치고 지운다 — 다른
 * build 의 몫은 그 버전의 이력이라 건드리지 않는다.
 *
 * ## 트랜잭션은 부르는 쪽이 맡는다
 *
 * [rewrite] 는 비교와 쓰기만 한다. 새 케이스를 folder 에 넣지 않는다 — 버전별 목록이 그 build 의 케이스를
 * 보이고, folder 는 사용자가 모으는 곳이다(ARTEL-1023).
 * [kr.artel.orchestration.testcase.service.ContentMapTestCaseService] 가 트랜잭션 안에서 부른다.
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
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val objectMapper: ObjectMapper,
) {

    /**
     * build [gameBuildId] 의 content map 이 낸 케이스를 그 버전 몫(`test_case_version`)으로 앉힌다(ARTEL-1018).
     *
     * **케이스의 정체는 프로젝트 전체에서 하나다.** 같은 `case_key` 는 어느 build 의 지도가 내도 같은
     * `test_case` 행이고, build 마다 달라지는 것은 버전 몫에 남는다. 정체가 없으면 새로 만든다. 정체의 내용 칸은 끝에서 가장 최근 버전을 비춘다(`syncIdentities`).
     *
     * - 새 버전 몫은 앞선 버전의 판정이 VERIFIED · FAILED · REGRESSION 이면 `REGRESSION` 으로 시작한다.
     *   앞 버전에서 판정이 난 것을 이번 버전에서 다시 봐야 하기 때문이다. 앞 버전 몫은 바꾸지 않는다 — 그
     *   행이 그 버전의 이력이다.
     * - 이번 생성에 없는 이 build 의 버전 몫은 지운다. 그래서 버전이 하나도 안 남은 정체는, 시나리오가
     *   인용하면 `BROKEN` 으로 남기고 아니면 지운다.
     *
     * 트랜잭션 안에서 불러야 한다 — 생성 · 갱신 · 삭제가 한쪽만 커밋되면 표가 서로 다른 시점을 가리킨다.
     */
    suspend fun rewrite(projectId: Long, gameBuildId: Long, contentMapId: Long): Result {
        val generated = generator.generate(contentMapId).distinctBy(::identityOf)
        val identities = testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter(::isMapOrigin)
        val byKey = identities.mapNotNull { row -> keyOf(row)?.let { it to row } }.toMap()
        // **키로 못 찾은 줄은 보이는 네 칸으로 한 번 더 찾는다**(ARTEL-617). 키가 생기기 전에 이 지도가
        // 앉힌 행에는 키가 없고, 그 줄을 새 줄로 치면 같은 케이스가 두 벌이 된다.
        val byText = identities.filter { keyOf(it) == null && isFrom(it, contentMapId) }.groupBy(::visibleIdentityOf)
        // 이 build 의 몫 중 **지도 케이스의 것만** 다룬다(ARTEL-1031). 사용자 생성 케이스의 몫(QA 판정이나 사람이 넣은 것)은
        // 지도에 없으니, 함께 읽으면 아래에서 "이 버전에서 사라진 케이스" 로 보고 지운다.
        val mapIds = identities.mapNotNull { it.id }.toSet()
        val versionsHere = versions.findByGameBuildId(gameBuildId).toList()
            .filter { it.testCaseId in mapIds }
            .associateBy { it.testCaseId }
        val previous = identities.mapNotNull { it.id }.takeIf { it.isNotEmpty() }
            ?.let { ids -> versions.findPreviousStatuses(ids, gameBuildId).toList().associate { it.testCaseId to it.verificationStatus } }
            .orEmpty()
        val claimed = mutableSetOf<Long>()

        var created = 0
        var updated = 0
        var regression = 0
        for (case in generated) {
            val identity = byKey[identityOf(case)]
                ?: byText[visibleIdentityOf(case)]?.firstOrNull { it.id !in claimed }
                ?: testCases.save(entityOf(projectId, contentMapId, case))
            val testCaseId = requireNotNull(identity.id)
            claimed += testCaseId
            val prior = versionsHere[testCaseId]
            if (prior == null) {
                val startsAs = if (previous[testCaseId] in JUDGED) REGRESSION else DRAFT
                if (startsAs == REGRESSION) regression++
                versions.save(versionOf(testCaseId, gameBuildId, contentMapId, case, startsAs))
                created++
                continue
            }
            // **보이는 칸까지 되돌린다**(ARTEL-617). 정체가 문장에서 지도로 옮겨 갔으므로 같은 줄이어도
            // 문구가 다를 수 있고, 되돌리지 않으면 문장 규칙을 고친 보람이 기존 행에 영영 닿지 않는다.
            val next = versionOf(
                testCaseId, gameBuildId, contentMapId, case,
                // 상했다고 돌려놓았던 줄이 지도에 다시 나타나면 성립한다는 뜻이다.
                if (prior.verificationStatus == BROKEN) DRAFT else prior.verificationStatus,
            ).copy(id = prior.id, verifiedAt = prior.verifiedAt, createdAt = prior.createdAt)
            if (versionChanged(prior, next)) {
                versions.save(next)
                updated++
            }
        }

        // 이번 생성이 자기 것이라 하지 않은 이 build 의 몫이 이 버전에서 사라진 케이스다.
        var deleted = 0
        var broken = 0
        val cited = testCases.findCaseIdsCitedByScenarios(projectId).toList().toSet()
        val gone = versionsHere.values.filterNot { it.testCaseId in claimed }
        for (stale in gone) {
            versions.delete(stale)
            if (versions.countByTestCaseId(stale.testCaseId) > 0) {
                deleted++
                continue
            }
            val identity = testCases.findById(stale.testCaseId) ?: continue
            if (stale.testCaseId in cited) {
                if (identity.verificationStatus != BROKEN) {
                    testCases.save(identity.copy(verificationStatus = BROKEN))
                    broken++
                }
            } else {
                testCases.delete(identity)
                deleted++
            }
        }

        val touched = claimed + gone.map { it.testCaseId }
        if (touched.isNotEmpty()) versions.syncIdentities(touched)
        return Result(
            created = created,
            updated = updated,
            deleted = deleted,
            broken = broken,
            regression = regression,
        )
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
     * 여기 실린 JSONB 칸이 넷(`metadata` · `condition` · `expected_items` · `expected_checks`)이라,
     * 넷 다 빼고 나머지를 견준 뒤 각각 따로 본다.
     */
    private fun versionChanged(prior: TestCaseVersionEntity, next: TestCaseVersionEntity): Boolean =
        prior.copy(
            metadata = next.metadata,
            condition = next.condition,
            expectedItems = next.expectedItems,
            expectedChecks = next.expectedChecks,
            updatedAt = next.updatedAt,
        ) != next ||
            !sameJson(prior.metadata, next.metadata) ||
            !sameJson(prior.condition, next.condition) ||
            !sameJson(prior.expectedItems, next.expectedItems) ||
            !sameJson(prior.expectedChecks, next.expectedChecks)

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

    private fun isMapOrigin(row: TestCaseEntity): Boolean =
        runCatching { objectMapper.readTree(row.metadata.asString()) }.getOrNull()
            ?.path(ORIGIN_FIELD)?.asText() == ORIGIN

    /** 키가 생기기 전의 줄을 찾을 때만 쓴다. 그 줄은 자기를 낸 content map 의 것만 견준다. */
    private fun isFrom(row: TestCaseEntity, contentMapId: Long): Boolean {
        val source = runCatching { objectMapper.readTree(row.metadata.asString()) }.getOrNull()
            ?.path(CONTENT_MAP_ID) ?: return false
        return source.canConvertToLong() && source.asLong() == contentMapId
    }

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
        expectedChecks = expectedChecksJson(case),
        status = case.status,
        capabilityKey = case.capabilityKey,
        metadata = metadataOf(contentMapId, case),
    )

    private fun versionOf(
        testCaseId: Long,
        gameBuildId: Long,
        contentMapId: Long,
        case: MapTestCase,
        verificationStatus: String,
    ) = TestCaseVersionEntity(
        testCaseId = testCaseId,
        gameBuildId = gameBuildId,
        contentMapId = contentMapId,
        scene = case.scene,
        step = case.step,
        precondition = case.precondition,
        condition = conditionJson(case),
        expectedValue = case.expected,
        expectedItems = expectedItemsJson(case),
        expectedChecks = expectedChecksJson(case),
        status = case.status,
        capabilityKey = case.capabilityKey,
        metadata = metadataOf(contentMapId, case),
        verificationStatus = verificationStatus,
    )

    /**
     * 기대결과 항목들을 그대로 싣는다(V93). 목록이 비면 null 이다 — 지도를 못 되짚는 행과 같은
     * 값이어야 하고, 빈 배열은 "기대결과가 없다"는 다른 말이다.
     */
    private fun expectedItemsJson(case: MapTestCase): Json? = case.expectedItems
        .takeIf { it.isNotEmpty() }
        ?.let { objectMapper.writeValueAsString(it) }
        ?.let(Json::of)

    /** 기대 항목마다 판정 구조를 싣는다. 항목과 같은 이유로 비면 null 이다. */
    private fun expectedChecksJson(case: MapTestCase): Json? = case.expectedChecks
        .takeIf { it.isNotEmpty() }
        ?.let { objectMapper.writeValueAsString(it) }
        ?.let(Json::of)

    /**
     * 출처와, 이 줄이 어느 지도에서 왔는지와, 등급이 그렇게 나온 이유를 싣는다.
     *
     * `test_case` 에는 `content_map_id` 칸이 없다(ARTEL-553 이 키만 냈다). 지도가 여럿인 프로젝트에서
     * 어느 지도의 산물인지 알아야 갈아 끼울 범위를 좁힐 수 있으므로 여기 적어 둔다. [isFrom] 이 이 값으로
     * 범위를 좁힌다(ARTEL-971).
     */
    private fun metadataOf(contentMapId: Long, case: MapTestCase): Json = Json.of(
        objectMapper.writeValueAsString(
            buildMap {
                put(ORIGIN_FIELD, ORIGIN)
                put(CONTENT_MAP_ID, contentMapId)
                put("gaps", case.gaps)
                // 문장과 무관한 정체(ARTEL-617). 다음 적재가 이것으로 같은 줄을 찾는다.
                put(CASE_KEY, case.identity)
                // 토글(ARTEL-1209). 시나리오가 단계 순서를 세울 때 읽는다.
                if (case.toggles.isNotEmpty()) put(TOGGLES, case.toggles)
                // 준비할 상태(ARTEL-1209). QA 계약의 `cannot_check` 로 나간다.
                if (case.setup.isNotEmpty()) put(SETUP, case.setup)
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
        val regression: Int = 0,
    )

    companion object {
        /** 이 경로가 앉힌 행이라는 표시. 이 값이 없는 행은 남의 것이라 건드리지 않는다. */
        const val ORIGIN = "content-map"
        const val ORIGIN_FIELD = "origin"

        /** 이 행을 낸 content map. [rewrite] 가 이 값으로 자기 행을 고른다. */
        private const val CONTENT_MAP_ID = "content_map_id"

        /** 이 케이스를 실행하면 되는 화면. 씬 전환이 아니면 없다. */
        const val ARRIVES_AT = "arrives_at"

        /** 문장과 무관한 정체(ARTEL-617). 지도 키와 그 케이스를 낸 효과로 만든다. */
        const val CASE_KEY = "case_key"
        const val TOGGLES = "toggles"
        const val SETUP = "setup"
        private const val BROKEN = "BROKEN"
        private const val DRAFT = "DRAFT"
        private const val REGRESSION = "REGRESSION"

        /** 앞 버전에서 이 상태였으면 새 버전 몫은 다시 봐야 할 것으로 시작한다. */
        private val JUDGED = setOf("VERIFIED", "FAILED", REGRESSION)
    }
}
