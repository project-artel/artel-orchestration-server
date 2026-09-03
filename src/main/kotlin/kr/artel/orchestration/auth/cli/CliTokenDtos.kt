package kr.artel.orchestration.auth.cli

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kr.artel.orchestration.auth.entity.CliTokenEntity
import kr.artel.orchestration.auth.entity.MAX_CLI_TOKEN_NAME_LENGTH
import java.time.Instant

/**
 * `POST /api/auth/cli-tokens` 요청 본문.
 *
 * [expiresInDays] 가 null 이면 만료가 없다. 그 선택은 명시적이어야 하므로 키 자체를 빠뜨린
 * 요청은 400 이다 — `@JsonProperty(required = true)` 를 creator 파라미터에 두면 Jackson 이
 * 키가 없을 때 파싱을 실패시킨다. 기본값을 두지 않는 것이 함께 필요하다. 기본값이 있으면
 * jackson-module-kotlin 이 없는 키를 그 값으로 채우고 `required` 에 닿지 않는다.
 *
 * 전역 `FAIL_ON_MISSING_CREATOR_PROPERTIES` 를 켜는 선택지는 다른 DTO 전부의 동작을 바꾸므로
 * 쓰지 않는다.
 */
data class CreateCliTokenRequest(
    @field:NotBlank
    @field:Size(max = MAX_CLI_TOKEN_NAME_LENGTH)
    val name: String,

    @JsonProperty(required = true)
    val expiresInDays: Int?
)

/**
 * 발급 응답. 토큰 원문이 나가는 유일한 응답이고, 그 사실이 타입으로 강제된다 —
 * [CliTokenResponse] 에는 `token` 필드가 없다.
 */
data class CreatedCliTokenResponse(
    val id: String,
    val name: String,
    /** 다시는 나오지 않는다. 서버는 해시만 갖고 있다. */
    val token: String,
    val createdAt: Instant,
    val expiresAt: Instant?
)

/** 목록의 한 줄. 원문이 없으므로 이 응답이 새어도 아무 계정도 열리지 않는다. */
data class CliTokenResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    /** 5분 해상도다. null 이면 발급 뒤 한 번도 쓰이지 않았다. */
    val lastUsedAt: Instant?,
    /** null 이면 만료가 없다. */
    val expiresAt: Instant?,
    /** 채워져 있으면 이 토큰으로는 더 이상 아무 요청도 통하지 않는다. */
    val revokedAt: Instant?
)

/** id 는 문자열이다. 레포 관례이고, 64비트 정밀도가 브라우저에서 깎이지 않는다. */
fun CliTokenEntity.toResponse() = CliTokenResponse(
    id = requireNotNull(id).toString(),
    name = name,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    expiresAt = expiresAt,
    revokedAt = revokedAt
)
