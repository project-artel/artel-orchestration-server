package kr.artel.orchestration.developermacro.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/** macro 이름 규칙. 진입점 `def` 의 이름이라 Python identifier 다. 경로에도 실리므로 이 서버가 먼저 거른다. */
const val DEVELOPER_MACRO_NAME_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$"

/** `developer_macro.name` 의 폭(V116). */
const val MAX_DEVELOPER_MACRO_NAME_LENGTH = 200

/** `developer_macro.source` 의 상한(V116). 기존 `macro.source` 와 같다. */
const val MAX_DEVELOPER_MACRO_SOURCE_LENGTH = 20_000

/**
 * 요청 본문의 build id. 이 저장소의 다른 id 처럼 JS 64비트 정밀도 손실을 피하려고 문자열로 오간다
 * (`GameBuildResponse.id`). 숫자로 읽히지 않으면 DTO 검증의 400 이다. 18자리까지면 `Long` 에 들어간다.
 */
const val WIRE_ID_PATTERN = "^[0-9]{1,18}$"

/** `developer_macro.docstring` 의 폭(V116). */
const val MAX_DEVELOPER_MACRO_DOCSTRING_LENGTH = 2_000

/**
 * 검사 요청. 저장하지 않는다.
 *
 * @property gameBuildId 이 macro 를 쓸 때 기준으로 삼은 build(`GameBuildResponse.id`). 같은 프로젝트의 것이어야 한다.
 *   문자열 id 다([WIRE_ID_PATTERN]). nullable 로 받고 `@NotBlank` 로 거르는 이유는 빠진 필드를 역직렬화 오류가 아니라
 *   필드 이름이 실린 검증 400 으로 돌려주기 위해서다
 */
data class CheckDeveloperMacroRequest(
    @field:NotBlank
    @field:Size(max = MAX_DEVELOPER_MACRO_NAME_LENGTH)
    @field:Pattern(regexp = DEVELOPER_MACRO_NAME_PATTERN, message = "Python identifier 여야 합니다.")
    val name: String,

    @field:NotBlank
    @field:Size(max = MAX_DEVELOPER_MACRO_SOURCE_LENGTH)
    val source: String,

    @field:NotBlank
    @field:Pattern(regexp = WIRE_ID_PATTERN, message = "숫자로 된 id 여야 합니다.")
    val gameBuildId: String?,
)

/** 등록 요청. 이름은 경로에 있다. [gameBuildId] 는 [CheckDeveloperMacroRequest.gameBuildId] 와 같다. */
data class PutDeveloperMacroRequest(
    @field:NotBlank
    @field:Size(max = MAX_DEVELOPER_MACRO_SOURCE_LENGTH)
    val source: String,

    @field:NotBlank
    @field:Pattern(regexp = WIRE_ID_PATTERN, message = "숫자로 된 id 여야 합니다.")
    val gameBuildId: String?,
)

/**
 * 검사 결과. 통과면 `{ok: true, parameters, docstring}`, 거절이면 `{ok: false, rejection}` 이다.
 *
 * 거절은 200 이다 — 검사 요청 자체는 성공했고 결과가 "통과하지 못함" 이다. 같은 거절이 등록(`PUT`)에서는
 * 400 `developer_macro_rejected` 가 된다.
 */
data class DeveloperMacroCheckResponse(
    val ok: Boolean,
    /** 진입점 parameter 이름, 순서대로. 거절이면 null. */
    val parameters: List<String>?,
    /** 진입점 docstring. 거절이면 null. */
    val docstring: String?,
    /** 거절 이유. 통과면 null. */
    val rejection: DeveloperMacroRejection?,
)

/** @property line 원문의 1부터 세는 줄 번호. 줄을 특정할 수 없는 거절이면 null */
data class DeveloperMacroRejection(
    val line: Int?,
    val reason: String,
)

/**
 * 마지막으로 등록한 사람. 사용자를 지웠으면 응답에서 이 객체가 null 이다.
 *
 * @property id `app_user.id`. 다른 응답의 사용자 id(`ProjectMemberResponse.userId`)처럼 문자열이다
 */
data class DeveloperMacroAuthor(
    val id: String,
    val nickname: String,
)

/** 목록 응답 봉투. */
data class DeveloperMacroListResponse(
    val items: List<DeveloperMacroSummaryResponse>,
)

/**
 * 목록 한 줄. 원문(`source`)은 싣지 않는다 — 한 건이 최대 20,000자다.
 *
 * @property parameters 진입점 parameter 이름, 순서대로
 * @property gameBuildId 마지막 등록이 기준으로 삼은 build. `GameBuildResponse.id` 와 같은 문자열 id 다
 * @property buildVersion 그 build 의 `version`(SDK 가 보고한 게임 버전)
 * @property buildLabel 그 build 에 사람이 붙인 이름. 없으면 null
 * @property version 정의가 실제로 바뀔 때만 오르는 번호. 처음 등록이 1 이다
 */
data class DeveloperMacroSummaryResponse(
    val name: String,
    val parameters: List<String>,
    val docstring: String,
    val gameBuildId: String,
    val buildVersion: String,
    val buildLabel: String?,
    val version: Int,
    val updatedAt: Instant,
    val updatedBy: DeveloperMacroAuthor?,
)

/** 상세. 목록 한 줄에 원문을 더한 것이고, 등록(`PUT`)의 응답도 이 모양이다. */
data class DeveloperMacroDetailResponse(
    val name: String,
    val source: String,
    val parameters: List<String>,
    val docstring: String,
    val gameBuildId: String,
    val buildVersion: String,
    val buildLabel: String?,
    val version: Int,
    val updatedAt: Instant,
    val updatedBy: DeveloperMacroAuthor?,
)

/** 내부 목록 응답 봉투(agent-server 엔진용). 항목은 원문을 싣는다. */
data class InternalDeveloperMacroListResponse(
    val items: List<DeveloperMacroDetailResponse>,
)
