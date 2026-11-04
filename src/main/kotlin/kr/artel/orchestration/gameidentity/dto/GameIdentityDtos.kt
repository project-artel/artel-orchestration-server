package kr.artel.orchestration.gameidentity.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kr.artel.orchestration.developermacro.dto.WIRE_ID_PATTERN
import java.time.Instant

/**
 * 선언 이름 규칙. 게임 state 가 부르는 `Type.member` 라 점으로 이은 identifier 가 둘 이상이다.
 * 점이 없는 이름은 어느 타입의 값인지 가를 수 없어 받지 않는다.
 */
const val GAME_IDENTITY_NAME_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"

/** `game_identity.kind` 의 CHECK 와 같은 집합(V173). */
const val GAME_IDENTITY_KIND_PATTERN = "^(ordinal|enum|id|flag|text)$"

/** `game_identity.role` 의 CHECK 와 같은 집합(V173). */
const val GAME_IDENTITY_ROLE_PATTERN = "^(current|progress|other)$"

/**
 * `identifies` 규칙. 소문자 영단어를 공백 하나 또는 `-` 로 잇는다. 같은 대상을 가리키는 선언끼리 글자 그대로 묶이도록
 * 대소문자·공백이 갈리는 표기를 받지 않는다.
 */
const val GAME_IDENTITY_IDENTIFIES_PATTERN = "^[a-z0-9]+([ -][a-z0-9]+)*$"

/** `game_identity` 의 칸 폭(V173). */
const val MAX_GAME_IDENTITY_NAME_LENGTH = 200
const val MAX_GAME_IDENTITY_MEANING_LENGTH = 500
const val MAX_GAME_IDENTITY_CHANGES_WHEN_LENGTH = 500
const val MAX_GAME_IDENTITY_IDENTIFIES_LENGTH = 60

/**
 * 등록 요청. developer macro 와 달리 이름이 경로가 아니라 본문에 있다. 이름 규칙은 DTO 검증이 거른다.
 *
 * nullable 로 받고 `@NotBlank`·`@NotNull` 로 거르는 이유는 `CheckDeveloperMacroRequest.gameBuildId` 와 같다 — 빠진 필드를
 * 역직렬화 오류가 아니라 필드 이름이 실린 검증 400 으로 돌려주기 위해서다.
 *
 * @property gameBuildId 이 revision을 선택할 build(`GameBuildResponse.id`). 같은 프로젝트의 것이어야 한다.
 *   문자열 id 다([WIRE_ID_PATTERN])
 * @property base `ordinal` 이 세기 시작하는 수. 0 또는 1, 선언하지 않으면 null
 * @property identifies 값이 서로 가르는 대상의 짧은 소문자 명사. 예: `stage`, `map chapter`, `enemy wave`
 * @property role `current`(지금 고른·들어간 것), `progress`(플레이가 어디까지 왔는지), `other`(둘 다 아님) 중 하나
 */
data class PutGameIdentityRequest(
    @field:NotBlank
    @field:Size(max = MAX_GAME_IDENTITY_NAME_LENGTH)
    @field:Pattern(regexp = GAME_IDENTITY_NAME_PATTERN, message = "`Type.member` 모양이어야 합니다.")
    val name: String?,

    @field:NotNull
    @field:Min(0)
    val expectedVersion: Int? = null,

    @field:NotBlank
    @field:Pattern(regexp = WIRE_ID_PATTERN, message = "숫자로 된 id 여야 합니다.")
    val gameBuildId: String?,

    @field:NotNull
    @field:Pattern(regexp = GAME_IDENTITY_KIND_PATTERN, message = "ordinal, enum, id, flag, text 중 하나여야 합니다.")
    val kind: String?,

    @field:Min(0)
    @field:Max(1)
    val base: Int? = null,

    @field:NotBlank
    @field:Size(max = MAX_GAME_IDENTITY_MEANING_LENGTH)
    val meaning: String?,

    @field:NotBlank
    @field:Size(max = MAX_GAME_IDENTITY_CHANGES_WHEN_LENGTH)
    val changesWhen: String?,

    @field:NotBlank
    @field:Size(max = MAX_GAME_IDENTITY_IDENTIFIES_LENGTH)
    @field:Pattern(regexp = GAME_IDENTITY_IDENTIFIES_PATTERN, message = "소문자 영단어를 공백이나 `-` 로 이은 명사여야 합니다.")
    val identifies: String?,

    @field:NotNull
    @field:Pattern(regexp = GAME_IDENTITY_ROLE_PATTERN, message = "current, progress, other 중 하나여야 합니다.")
    val role: String?,
)

/**
 * 저장된 선언 한 줄. 등록(`PUT`)의 응답도 이 모양이다.
 *
 * @property id `revisionId`와 같은 revision id. 이 저장소의 다른 id처럼 문자열이다
 * @property gameBuildId 이 revision을 등록한 build. `GameBuildResponse.id` 와 같은 문자열 id 다
 * @property version 프로젝트·이름별로 증가하는 revision 번호. build의 선택은 서로 독립적이다
 */
data class GameIdentityResponse(
    val id: String,
    val name: String,
    val kind: String,
    val base: Int?,
    val meaning: String,
    val changesWhen: String,
    val identifies: String,
    val role: String,
    val gameBuildId: String,
    val version: Int,
    val updatedAt: Instant,
    val revisionId: String,
    val source: String,
    val deleted: Boolean,
    val createdBy: String?,
    val createdAt: Instant,
)

/** 목록 응답 봉투. */
data class GameIdentityListResponse(
    val identities: List<GameIdentityResponse>,
)

/** agent-server 엔진이 읽는 한 줄. 사람이 고르는 데 쓰는 `id`·`updatedAt` 은 싣지 않는다. */
data class InternalGameIdentityResponse(
    val name: String,
    val kind: String,
    val base: Int?,
    val meaning: String,
    val changesWhen: String,
    val identifies: String,
    val role: String,
    val gameBuildId: String,
    val version: Int,
    val revisionId: String,
    val source: String,
    val deleted: Boolean,
    val createdBy: String?,
    val createdAt: Instant,
)

/** 내부 목록 응답 봉투(agent-server 엔진용). */
data class InternalGameIdentityListResponse(
    val identities: List<InternalGameIdentityResponse>,
)

/** SDK frame의 build와 사용자 정보는 인증된 instance에서 정한다. */
data class SdkGameIdentityCatalog(val type: String, val identities: List<SdkGameIdentityDeclaration>)
data class SdkGameIdentityDeclaration(
    val name: String, val kind: String, val base: Int? = null, val meaning: String,
    val changesWhen: String, val identifies: String, val role: String,
) {
    fun forBuild(buildId: Long) = PutGameIdentityRequest(
        name = name, gameBuildId = buildId.toString(), expectedVersion = 0,
        kind = kind, base = base, meaning = meaning, changesWhen = changesWhen,
        identifies = identifies, role = role,
    )
}
