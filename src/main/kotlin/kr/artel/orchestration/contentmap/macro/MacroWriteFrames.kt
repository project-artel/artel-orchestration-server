package kr.artel.orchestration.contentmap.macro

import com.fasterxml.jackson.databind.JsonNode

/**
 * QA agent 가 등록한 macro 를 지도에 적고 다시 읽는 프레임(ARTEL-921).
 *
 * **이 파일이 macro 프레임의 계약이다.** `docs/capability-write-frames.md` 는 macro 를 다루지
 * 않는다 — 거기서 가져온 것은 모양뿐이고(같은 봉투, 같은 거절 방식, 같은 라우터 가드), 이 두
 * 타입이 무엇을 받고 무엇을 거절하는지는 이 파일에만 적혀 있다. payload 필드 표는 PR 본문에 있다
 * (ARTEL-921 의 Validation Notes 가 요구한 형태다).
 *
 * ```
 * AGENT_TO_ORCHE  MACRO_REGISTER      등록된 macro 정의를 적는다
 * AGENT_TO_ORCHE  MACRO_READ          저장된 정의를 이름으로 읽는다
 * ORCHE_TO_AGENT  MACRO_WRITE_RESULT  MACRO_REGISTER 의 답
 * ORCHE_TO_AGENT  MACRO_READ_RESULT   MACRO_READ 의 답
 * ORCHE_TO_AGENT  ERROR               둘 다에 대한 거절. correlationId 가 요청을 문다
 * ```
 *
 * 거절이 성공과 다른 타입인 것은 지식 쓰기(ARTEL-331) · 이슈 보고(ARTEL-366) · 지도 쓰기
 * (ARTEL-644)가 이미 그 계약이기 때문이다. agent 쪽은 correlation 하나로 대기를 풀고, 요청
 * 종류마다 실패 모양이 달라지지 않는다.
 *
 * ## 응답 타입을 둘로 가르는 이유
 *
 * `CAPABILITY_WRITE_RESULT` 가 쓰기 둘에 하나로 답한 것은 그 둘이 **쓰기 한 가족**이고 응답이
 * 필드 몇 개만 달랐기 때문이다. 여기는 쓰기 하나와 읽기 하나라 응답의 모양 자체가 다르다 —
 * 쓰기는 id 를 싣고 읽기는 `source` 와 tree 를 싣는다. `KNOWLEDGE_WRITE_RESULT` 와
 * `KNOWLEDGE_SEARCH_RESULT` 가 갈린 것과 같은 판단이다.
 *
 * 쓰기 쪽을 [WRITE_RESULT] 하나로 두어 다음 macro 쓰기 타입이 계약을 물려받는다.
 *
 * ## `ContentMapMode.FROZEN` 게이트가 아직 없다
 *
 * 이 두 타입도 지도에 쓰므로 `frozen` 런에서 막혀야 하지만, 그 게이트를 잇는 것은 ARTEL-922 다.
 * 지금은 자리만 비어 있다 — `QaAgentInboundRouter.routeMacro` 위의 주석이 그 자리를 가리킨다.
 */
object MacroWriteFrames {

    /** 등록된 macro 정의를 적는다. `AGENT_TO_ORCHE`. */
    const val REGISTER: String = "MACRO_REGISTER"

    /** 저장된 정의를 이름으로 읽는다. `AGENT_TO_ORCHE`. */
    const val READ: String = "MACRO_READ"

    /** [REGISTER] 의 답. `ORCHE_TO_AGENT`. */
    const val WRITE_RESULT: String = "MACRO_WRITE_RESULT"

    /** [READ] 의 답. `ORCHE_TO_AGENT`. */
    const val READ_RESULT: String = "MACRO_READ_RESULT"

    /** 이 두 타입이 이 파일의 계약을 진다. */
    val INBOUND: Set<String> = setOf(REGISTER, READ)

    /**
     * `name` 상한. `macro.name` 이 `VARCHAR(200)` 이라 넘으면 DB 오류가 된다.
     *
     * Kotlin 에서 먼저 거르는 이유: DB 오류는 제약 이름만 실려 agent 가 무엇을 고쳐야 하는지
     * 읽을 수 없고, 그 예외가 receive 체인 밖으로 나가면 WebSocket 이 닫혀 런 전체가 실패한다.
     */
    const val MAX_NAME_LENGTH: Int = 200

    /**
     * `source` 상한. `macro.source` 는 `TEXT` 라 DB 가 막아 주지 않는다.
     *
     * 상한을 두는 이유는 `rationale` · `summary` 에 둔 것과 같다 — agent 가 보낸 텍스트가 상한
     * 없이 들어오면 프레임 하나가 타임라인과 이 표를 밀어낸다.
     *
     * 20,000자의 근거: macro 하나는 `def` 몇 개짜리 script 다. 실측 예시
     * `attack_with_combined_card` 가 300자 미만이라 두 자리 넉넉한 값이고, 이 값에 걸리는 정의는
     * macro 가 아니라 다른 것이다.
     */
    const val MAX_SOURCE_LENGTH: Int = 20_000

    /**
     * `definition` 의 직렬화 상한. [MAX_SOURCE_LENGTH] 와 같은 이유로 둔다.
     *
     * [MAX_SOURCE_LENGTH] 의 10배다. tree 는 같은 내용을 key 와 괄호로 부풀려 담으므로 텍스트보다
     * 크고, 그 비율은 statement 종류에 따라 흔들린다. 파생물이 원본보다 10배를 넘으면 그것은
     * 부풀기가 아니라 다른 문제다.
     */
    const val MAX_DEFINITION_LENGTH: Int = 200_000

    /** `require` statement 의 `kind` 값. [REMEDY_FIELD] 와 함께 DB CHECK 가 보는 두 key 다. */
    const val REQUIRE_KIND: String = "require"

    /** `require` 가 반드시 드는 칸. 비어 있으면 거절한다. */
    const val REMEDY_FIELD: String = "remedy"

    /** statement 의 종류를 말하는 칸. */
    const val KIND_FIELD: String = "kind"
}

/**
 * [MacroWriteFrames.REGISTER] 의 payload.
 *
 * [definition] 만 `JsonNode` 다. 이 서비스는 그것을 `jsonb` 칸으로 그대로 넘기고 필드를 읽지
 * 않는다 — tree 의 모양은 ARTEL-918 이 확정하고, 여기서 타입으로 못 박으면 그쪽이 정할 때마다
 * 이 파일이 따라 바뀐다. 유일한 예외가 `require` 의 `remedy` 검사이고, 그것은 경계에서 한 번
 * 보는 것이다.
 *
 * [screens] 는 `screen.id` 를 **JSON 문자열**로 담는다. `CAPABILITY_VERDICT.screen_id` 가 이미
 * 쓰는 규약을 그대로 쓴다 — 64비트 id 가 JSON 숫자로 오가면 자바스크립트 소비자에서 깎인다.
 * 기존 관계에 **더한다.** 지우지 않는다(ARTEL-925).
 */
data class MacroRegisterRequest(
    val name: String? = null,
    val source: String? = null,
    val definition: JsonNode? = null,
    /** 진입점 `def` 줄의 parameter 이름. **순서가 뜻을 가진다.** */
    val parameters: List<String> = emptyList(),
    val screens: List<String> = emptyList(),
)

/** [MacroWriteFrames.READ] 의 payload. 이름 하나로 조회한다. */
data class MacroReadRequest(
    val name: String? = null,
)

/**
 * macro 쓰기 · 읽기 하나의 결과. **거절을 예외로 올리지 않는다.**
 *
 * 라우터가 이것을 읽어 성공은 [MacroWriteFrames.WRITE_RESULT] 또는
 * [MacroWriteFrames.READ_RESULT], 거절은 요청의 correlation 을 문 `ERROR` 프레임으로 답한다.
 * 예외로 올리면 receive 체인이 끊겨 프레임 하나가 QA 런 전체를 실패시킨다 —
 * `CapabilityWrite` 와 `KnowledgeMutation` 이 같은 모양을 이미 쓰고 있다.
 */
sealed interface MacroWrite {

    /** [MacroWriteFrames.REGISTER] 가 받아들여졌다. */
    data class Registered(
        val macroId: Long,
        val name: String,
        /** 행을 새로 넣었나. 같은 이름을 다시 등록한 갱신이면 `false` 다. */
        val created: Boolean,
        /** 관계를 더한 **뒤의** 상태. */
        val screenIds: List<Long>,
    ) : MacroWrite

    /** [MacroWriteFrames.READ] 가 정의를 찾았다. */
    data class Loaded(
        val macroId: Long,
        val name: String,
        val source: String,
        /** 저장된 tree 그대로. 실행하는 쪽이 이것만 본다. */
        val definition: JsonNode,
        val parameters: List<String>,
        val screenIds: List<Long>,
    ) : MacroWrite

    data class Rejected(val reason: String) : MacroWrite
}
