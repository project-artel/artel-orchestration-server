package kr.artel.orchestration.contentmap.dto

import io.swagger.v3.oas.annotations.media.Schema
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.GroupKind

/**
 * 조작 단계 하나가 성립하는 **조건**. `capability_evidence.condition_tree` 를 화면 어휘로 옮긴 것.
 *
 * **왜 이 트리가 응답에 필요한가.** 실측 GameClearScene 의 `runnable` 6개는 `summary` · `inputKey` ·
 * `status` 가 전부 같다. `given_text` 는 아직 51건 전부 null 이라(ARTEL-447 미완) 화면에서 여섯 줄이
 * 똑같아 보이는데, 실제로는 `condition_tree` 가 전부 달라 **조건만 다른 여섯 갈래**다. 이 칸이
 * 없으면 화면은 같은 줄 여섯 개를 그리고 사람은 그것을 중복 버그로 읽는다.
 *
 * **내부 모델([ConditionNode])을 그대로 직렬화하지 않는다.** 그러면 `Group(kind=EVERY, parts=[…])`
 * 처럼 계약에 없는 모양이 나간다 — 대문자 enum 이고, `every` 인지 `either` 인지를 알려면 `kind` 와
 * 그룹 종류 두 칸을 봐야 한다. 여기서는 그 둘을 [Every] · [Either] 로 **펴서**, 화면이 `kind`
 * 한 칸만 보고 갈래를 정할 수 있게 한다.
 *
 * 규칙 둘:
 * - `kind` 는 **늘 소문자**다. 지저분한 것은 서버가 흡수하고 화면은 한 어휘만 안다
 * - **이름표 없는 노드는 나가지 않는다.** 모르는 조건도 [Unknown] 이라는 이름을 달고 나간다 —
 *   버리면 "조건이 없다"가 되고, 그것은 근거가 하지 않은 말이다
 *
 * **평탄화 금지.** `either` 를 `every` 로 접으면 "둘 중 하나"가 "둘 다"가 되어, 영영 성립하지 않는
 * 조건이 명세로 나간다. 이 DTO 는 원본 모양을 그대로 옮기기만 한다.
 *
 * 응답 전용이라 역직렬화 배선(`@JsonTypeInfo`)이 없다. 변종마다 `kind` 를 고정 문자열 프로퍼티로
 * 들고 있어 Jackson 이 그것을 그대로 낸다. 그래서 **생성은 늘 이름 붙인 인자로 한다** — `kind` 가
 * 맨 앞이라 위치 인자를 쓰면 조용히 그 칸에 들어간다.
 */
@Schema(
    description = "조건 트리 노드. kind 가 갈래를 정한다",
    subTypes = [
        ConditionNodeResponse.Always::class,
        ConditionNodeResponse.Test::class,
        ConditionNodeResponse.Gesture::class,
        ConditionNodeResponse.Every::class,
        ConditionNodeResponse.Either::class,
        ConditionNodeResponse.Unknown::class,
    ],
)
sealed interface ConditionNodeResponse {

    /** 늘 소문자. 화면이 갈래를 정할 때 보는 유일한 칸이다. */
    @get:Schema(description = "always · test · gesture · every · either · unknown")
    val kind: String

    /** 조건 없음. 이 단계는 씬에 들어서면 바로 성립한다. */
    @Schema(description = "조건 없음")
    data class Always(override val kind: String = "always") : ConditionNodeResponse

    /**
     * 비교 하나.
     *
     * @property context null 이면 **주어를 못 찾은 것**이고 [subjectLost] 가 그 사유를 든다.
     *   그 조건은 given 으로 쓸 수 없다 — 여기서 주어를 상상하는 것이 가장 비싼 거짓 명세다
     * @property offset 이 비교가 나온 IL 위치. 같은 메서드 안의 갈래를 가르는 값이다
     */
    @Schema(description = "비교 하나")
    data class Test(
        override val kind: String = "test",
        val left: String,
        val operator: String,
        val right: String,
        @Schema(description = "null 이면 주어를 못 찾았다. subjectLost 가 사유를 든다")
        val context: String?,
        @Schema(description = "주어를 잃은 사유. context 가 null 일 때만 찬다")
        val subjectLost: String?,
        val offset: Int,
    ) : ConditionNodeResponse

    /** 입력 조건. [input] 은 `key:RightArrow (down)` 처럼 근거가 이미 렌더해 준 문자열이다. */
    @Schema(description = "입력 조건")
    data class Gesture(
        override val kind: String = "gesture",
        @Schema(description = "`key:RightArrow (down)` 꼴. 근거가 렌더한 문자열 그대로다")
        val input: String,
        val offset: Int,
    ) : ConditionNodeResponse

    /** 모두 성립해야 한다. */
    @Schema(description = "parts 가 모두 성립해야 한다")
    data class Every(
        override val kind: String = "every",
        val parts: List<ConditionNodeResponse>,
    ) : ConditionNodeResponse

    /** 하나만 성립하면 된다. **`every` 로 접지 않는다.** */
    @Schema(description = "parts 중 하나만 성립하면 된다")
    data class Either(
        override val kind: String = "either",
        val parts: List<ConditionNodeResponse>,
    ) : ConditionNodeResponse

    /**
     * 근거가 못 읽은 조건. **있으면 조건을 단정하면 안 된다.**
     *
     * @property unread 무엇에서 막혔는지. `operand:ldlen` 처럼 IL 조각이다. 없을 수 있다
     */
    @Schema(description = "근거가 읽지 못한 조건. 있으면 조건을 단정하면 안 된다")
    data class Unknown(
        override val kind: String = "unknown",
        val reason: String,
        @Schema(description = "무엇에서 막혔는지. `operand:ldlen` 꼴")
        val unread: String?,
    ) : ConditionNodeResponse

    companion object {

        /**
         * 파서가 읽은 트리를 wire 모양으로 옮긴다. **모양만 바꾸고 구조는 건드리지 않는다.**
         *
         * [ConditionNode.Group] 이 [Every] · [Either] 로 펴지는 자리다. `when` 이 [GroupKind] 위에서
         * 남김없이 갈리므로, 그 열거형에 값이 하나 늘면 **여기가 컴파일 오류로 걸린다.** 문자열로
         * 갈랐다면 모르는 그룹이 조용히 어느 한쪽으로 떨어졌을 것이다.
         *
         * [ConditionNode.Unknown.reason] 은 모델에서 nullable 이지만 계약은 값을 요구한다. 파서가
         * `reason ?: kind` 로 채우므로 실제로는 늘 값이 있고, 그래도 비면 `"unknown"` 으로 떨어뜨린다 —
         * **이름표 없는 노드가 나가면 화면이 그 줄을 그릴 수 없다.**
         */
        fun of(node: ConditionNode): ConditionNodeResponse = when (node) {
            is ConditionNode.Always -> Always()
            is ConditionNode.Test -> Test(
                left = node.left,
                operator = node.operator,
                right = node.right,
                context = node.context,
                subjectLost = node.subjectLost,
                offset = node.offset,
            )
            is ConditionNode.Gesture -> Gesture(input = node.input, offset = node.offset)
            is ConditionNode.Group -> when (node.kind) {
                GroupKind.EVERY -> Every(parts = node.parts.map(::of))
                GroupKind.EITHER -> Either(parts = node.parts.map(::of))
            }
            is ConditionNode.Unknown -> Unknown(
                reason = node.reason ?: "unknown",
                unread = node.unread,
            )
        }
    }
}
