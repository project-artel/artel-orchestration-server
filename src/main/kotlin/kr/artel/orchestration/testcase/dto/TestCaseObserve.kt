package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.common.error.BadRequestException

/**
 * 이 케이스가 돌 때 SDK 가 읽어 보낼 관측 이름(ARTEL-1280). `Game.Player::hp`, `Progress::stage` 같은 문자열이고 SDK 가
 * 푼다 — orchestration 은 [TestCaseFunctions] 처럼 검증해 저장하고 건넬 뿐, 이름을 해석하지 않는다.
 *
 * 컬럼을 더하지 않고 `test_case.metadata` 의 `observe` 키에 둔다. 그래서 `metadata` 를 통째로 다시 쓰는 경로
 * ([kr.artel.orchestration.testcase.generator.MapTestCaseWriter] 의 재생성, 명세 재적재)는 이 키도 함께 덮는다. 사람이
 * 쓴 케이스는 그 경로가 닿지 않는다.
 */
object TestCaseObserve {
    const val FIELD = "observe"

    /**
     * 이름을 검사한다. 빈 이름과 null 은 400 이고, 같은 이름은 처음 자리에 한 번만 남긴다 — [TestCaseFunctions] 의 이름 목록과
     * 같은 규칙이다.
     */
    fun validated(observe: List<String?>): List<String> {
        val names = LinkedHashSet<String>()
        for (item in observe) {
            names += item?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequestException("observe must not contain a blank name")
        }
        return names.toList()
    }

    /**
     * [metadata] 의 `observe` 키만 바꾼다. 다른 키는 그대로 두고, 빈 목록이면 키를 지운다 — 응답과 agent case ref 는
     * "정한 적 없음" 과 "비움" 을 가르지 않고 둘 다 null 이다. 객체가 아닌 `metadata` 는 빈 객체로 본다.
     */
    fun write(metadata: Json, observe: List<String>, objectMapper: ObjectMapper): Json {
        val root = runCatching { objectMapper.readTree(metadata.asString()) }.getOrNull() as? ObjectNode
            ?: objectMapper.createObjectNode()
        if (observe.isEmpty()) root.remove(FIELD) else root.putArray(FIELD).also { names -> observe.forEach { names.add(it) } }
        return Json.of(root.toString())
    }
}
