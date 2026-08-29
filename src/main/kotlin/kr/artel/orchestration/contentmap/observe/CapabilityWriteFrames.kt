package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/**
 * QA agent 가 자기가 본 것을 지도에 적는 프레임(ARTEL-644).
 *
 * 문서는 `docs/capability-write-frames.md` 다. 문서와 이 파일이 어긋나면 **이 파일이 맞다.**
 * ARTEL-645 가 agent 쪽 tool 을 이 계약대로 만든다 — PR 135 는 없는 frame 을 agent-server 가
 * 지어냈다가 통째로 닫혔고, 그것을 되풀이하지 않으려고 계약을 이쪽에 못 박는다.
 *
 * ```
 * AGENT_TO_ORCHE  CAPABILITY_VERDICT       이 기능이 되더라 / 안 되더라
 * AGENT_TO_ORCHE  CAPABILITY_DISCOVERED    근거에 없던 기능을 찾았다
 * ORCHE_TO_AGENT  CAPABILITY_WRITE_RESULT  둘 다에 대한 성공 답
 * ORCHE_TO_AGENT  ERROR                    둘 다에 대한 거절. correlationId 가 요청을 문다
 * ```
 *
 * 성공과 거절이 다른 타입인 것은 지식 쓰기(ARTEL-331)와 이슈 보고(ARTEL-366)가 이미 그 계약이기
 * 때문이다. agent 쪽은 correlation 하나로 대기를 풀고, 요청 종류마다 실패 모양이 달라지지 않는다.
 */
object CapabilityWriteFrames {

    /** 이미 지도에 있는 기능에 대한 판단. */
    const val VERDICT: String = "CAPABILITY_VERDICT"

    /** 근거에 없던 기능을 새로 적는다. */
    const val DISCOVERED: String = "CAPABILITY_DISCOVERED"

    /**
     * 두 쓰기가 **하나의 타입**으로 답한다. `KNOWLEDGE_WRITE_RESULT` 와 같은 판단이다 — 응답이
     * 필드 몇 개만 다르고, 타입을 하나로 두면 다음 쓰기 타입이 계약을 자동으로 물려받는다.
     * 무엇의 답인지는 payload 의 `type` 이 말한다.
     */
    const val WRITE_RESULT: String = "CAPABILITY_WRITE_RESULT"

    /** 이 두 타입이 이 파일의 계약을 진다. */
    val INBOUND_TYPES: Set<String> = setOf(VERDICT, DISCOVERED)

    /** `rationale` 상한. 넘으면 거절한다 — 프레임 하나가 타임라인을 밀어내지 못하게 한다. */
    const val MAX_RATIONALE_LENGTH: Int = 2_000

    /** `summary` 상한. `capability.summary` 는 TEXT 지만 한 줄 설명이어야 한다. */
    const val MAX_SUMMARY_LENGTH: Int = 1_000
}

/** agent 의 판단. DB 의 `capability_observation.verdict` 값과 글자까지 같다. */
enum class CapabilityVerdict(val wire: String) {
    /** 되는 것을 봤다. `capability.verification` 이 `confirmed` 로 간다. */
    WORKS("works"),

    /** 안 되더라. `contradicted` 로 간다. 지우지 않는 이유는 `VerificationState` 의 KDoc 에 있다. */
    FAILS("fails");

    companion object {
        val NAMES: List<String> = entries.map { it.wire }

        fun from(wire: String?): CapabilityVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `CAPABILITY_VERDICT` 의 payload.
 *
 * capability 를 [capabilityKey] 로 지목하는 것이 기본이다. 그 키는 재적재를 넘어 살아남고
 * `v_content_map_capability` 가 이미 내주고 있다. [capabilityId] 는 방금
 * `CAPABILITY_DISCOVERED` 로 만든 행처럼 **키가 없는 행**을 지목할 때 쓴다 — observed · inferred
 * 출신은 `capability_key` 가 NULL 이다.
 *
 * [scene] 이 필수인 것이 거절 규칙의 축이다. agent 가 서 있지 않은 `scene` 의 capability 에
 * verdict 를 찍으면, 그 verdict 는 이 런이 실제로 본 것이 아니다.
 */
data class CapabilityVerdictRequest(
    val scene: String? = null,
    @JsonProperty("capability_key") val capabilityKey: String? = null,
    @JsonProperty("capability_id") val capabilityId: String? = null,
    val verdict: String? = null,
    val rationale: String? = null,
    @JsonProperty("capture_id") val captureId: String? = null,
    @JsonProperty("screen_id") val screenId: String? = null,
    val action: CapabilityActionRecord? = null,
)

/**
 * agent 가 실제로 보낸 조작. **재현이 필요한 곳은 content_map 이 아니라 이 기록이다.**
 *
 * 전부 선택이다. 실측 472 행 중 418 행이 `interaction = 'none'` 이라 보낼 메서드가 애초에 없다.
 */
data class CapabilityActionRecord(
    val method: String? = null,
    /** SDK 프로토콜의 인자. 읽지 않고 `action_params` jsonb 로 그대로 넘기는 값이다. */
    val params: JsonNode? = null,
    /** 첫 메서드가 거절당해 바꿔 성공한 횟수. `> 1` 이 쌓이는 자리가 힌트가 나쁜 자리다. */
    val attempts: Int? = null,
)

/**
 * `CAPABILITY_DISCOVERED` 의 payload.
 *
 * [origin] 은 `observed` 또는 `inferred` 만 받는다. `evidence` 를 받으면 agent 가 정적 분석의
 * 옷을 입은 행을 만들 수 있고, 그 순간 "이 행이 어디서 왔나" 가 답할 수 없는 질문이 된다.
 * `human` 도 같은 이유로 받지 않는다.
 *
 * [verdict] 는 `observed` 에 **필수**이고 `inferred` 에는 실을 수 없다. `observed` 가 곧 "눌러 보고
 * 결과를 봤다" 는 뜻이라 verdict 가 따라오는 것이 그 뜻이고, 그것이 [rationale] 이 반드시 어딘가에
 * 앉게 하는 유일한 길이기도 하다 — observation 행은 verdict 없이 서지 못한다.
 */
data class CapabilityDiscoveredRequest(
    val scene: String? = null,
    val origin: String? = null,
    val summary: String? = null,
    @JsonProperty("given_text") val givenText: String? = null,
    val interaction: String? = null,
    @JsonProperty("input_key") val inputKey: String? = null,
    @JsonProperty("input_phase") val inputPhase: String? = null,
    @JsonProperty("control_path") val controlPath: String? = null,
    @JsonProperty("control_label") val controlLabel: String? = null,
    val rationale: String? = null,
    @JsonProperty("capture_id") val captureId: String? = null,
    @JsonProperty("screen_id") val screenId: String? = null,
    val verdict: String? = null,
    /** [verdict] 를 만든 조작. [CapabilityVerdictRequest.action] 과 같은 값이다. */
    val action: CapabilityActionRecord? = null,
    /**
     * `inferred` 일 때 딛고 선 observation. `capability_observation.id` 목록이고 이 런의 것이어야 한다.
     * 비면 거절한다 — 근거를 밝히지 않은 추론은 그럴듯한 거짓말이고, `capability_inference` 가 존재하는
     * 이유가 그것을 막는 것이다.
     */
    @JsonProperty("based_on") val basedOn: List<String> = emptyList(),
)
