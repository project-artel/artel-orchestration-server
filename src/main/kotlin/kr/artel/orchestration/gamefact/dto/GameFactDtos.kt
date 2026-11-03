package kr.artel.orchestration.gamefact.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kr.artel.orchestration.developermacro.dto.WIRE_ID_PATTERN
import java.time.Instant

/** fact 이름 규칙. 결정 모델과 planner 의 guidance 가 이 이름 그대로 부른다. */
const val GAME_FACT_NAME_PATTERN = "^[a-z][a-z0-9_]{0,39}$"

/** rule 이름 규칙. */
const val GAME_RULE_NAME_PATTERN = "^[a-z][a-z0-9_]{0,59}$"

/** `game_fact.scope` 의 CHECK 와 같은 집합(V174). */
const val GAME_FACT_SCOPE_PATTERN = "^(objects|statics)$"

/** `game_fact.type` 의 CHECK 와 같은 집합(V174). */
const val GAME_FACT_TYPE_PATTERN = "^(integer|number|boolean|string)$"

/** 엔진이 읽는 이름. 점으로 이은 identifier 둘 이상(`Type.member`, `Type.member.Count`, `Type.Instance.member`). */
const val GAME_FACT_MEMBER_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"

/** 칸 폭(V174). */
const val MAX_GAME_FACT_MEMBER_LENGTH = 200
const val MAX_GAME_FACT_UNIT_LENGTH = 30
const val MAX_GAME_FACT_MEANS_LENGTH = 120
const val MAX_GAME_RULE_SAYS_LENGTH = 160

/** rule 하나가 말할 수 있는 fact 수의 상한. */
const val MAX_GAME_RULE_FACTS = 8

/**
 * fact 등록 요청. nullable 로 받고 `@NotBlank`·`@NotNull` 로 거르는 이유는 `PutGameIdentityRequest` 와 같다 — 빠진 필드를
 * 필드 이름이 실린 검증 400 으로 돌려주기 위해서다.
 *
 * @property member 엔진이 읽는 이름 그대로. 목록 크기는 `<member>.Count`, singleton 의 member 는 `Type.Instance.member`
 * @property means 값이 무엇인지 플레이어 말로. 무엇을 하라는 조언은 쓰지 않는다
 */
data class PutGameFactRequest(
    @field:NotBlank
    @field:Pattern(regexp = GAME_FACT_NAME_PATTERN, message = "snake_case 40자 이하여야 합니다.")
    val name: String?,

    @field:NotBlank
    @field:Pattern(regexp = WIRE_ID_PATTERN, message = "숫자로 된 id 여야 합니다.")
    val gameBuildId: String?,

    @field:NotNull
    @field:Pattern(regexp = GAME_FACT_SCOPE_PATTERN, message = "objects, statics 중 하나여야 합니다.")
    val scope: String?,

    @field:NotBlank
    @field:Size(max = MAX_GAME_FACT_MEMBER_LENGTH)
    @field:Pattern(regexp = GAME_FACT_MEMBER_PATTERN, message = "`Type.member` 모양이어야 합니다.")
    val member: String?,

    @field:NotNull
    @field:Pattern(regexp = GAME_FACT_TYPE_PATTERN, message = "integer, number, boolean, string 중 하나여야 합니다.")
    val type: String?,

    @field:Size(max = MAX_GAME_FACT_UNIT_LENGTH)
    val unit: String? = null,

    @field:NotBlank
    @field:Size(max = MAX_GAME_FACT_MEANS_LENGTH)
    val means: String?,
)

/**
 * rule 등록 요청.
 *
 * @property says 게임이 어떻게 도는지 한 문장. 조언이 아니다
 * @property facts 이 rule 이 말하는 fact 의 이름. 같은 프로젝트에 등록돼 있어야 한다
 */
data class PutGameRuleRequest(
    @field:NotBlank
    @field:Pattern(regexp = GAME_RULE_NAME_PATTERN, message = "snake_case 60자 이하여야 합니다.")
    val name: String?,

    @field:NotBlank
    @field:Pattern(regexp = WIRE_ID_PATTERN, message = "숫자로 된 id 여야 합니다.")
    val gameBuildId: String?,

    @field:NotBlank
    @field:Size(max = MAX_GAME_RULE_SAYS_LENGTH)
    val says: String?,

    @field:NotEmpty
    @field:Size(max = MAX_GAME_RULE_FACTS)
    val facts: List<@NotBlank @Pattern(regexp = GAME_FACT_NAME_PATTERN, message = "fact 이름 규칙에 맞아야 합니다.") String?>?,
)

/** 저장된 fact 한 줄. 등록(`PUT`)의 응답도 이 모양이다. */
data class GameFactResponse(
    val id: String,
    val name: String,
    val scope: String,
    val member: String,
    val type: String,
    val unit: String?,
    val means: String,
    val gameBuildId: String,
    val version: Int,
    val updatedAt: Instant,
)

/** 저장된 rule 한 줄. */
data class GameRuleResponse(
    val id: String,
    val name: String,
    val says: String,
    val facts: List<String>,
    val gameBuildId: String,
    val version: Int,
    val updatedAt: Instant,
)

/** 목록 응답. fact 와 rule 을 함께 싣는다 — rule 은 fact 를 가리키므로 따로 읽으면 짝이 맞지 않을 수 있다. */
data class GameFactListResponse(
    val facts: List<GameFactResponse>,
    val rules: List<GameRuleResponse>,
)

/** agent-server 엔진이 읽는 fact. 사람이 고르는 데 쓰는 `id`·`updatedAt` 은 싣지 않는다. */
data class InternalGameFactResponse(
    val name: String,
    val scope: String,
    val member: String,
    val type: String,
    val unit: String?,
    val means: String,
    val version: Int,
)

/** agent-server 엔진이 읽는 rule. */
data class InternalGameRuleResponse(
    val name: String,
    val says: String,
    val facts: List<String>,
    val version: Int,
)

/** 내부 목록 응답(agent-server 엔진용). */
data class InternalGameFactListResponse(
    val facts: List<InternalGameFactResponse>,
    val rules: List<InternalGameRuleResponse>,
)
