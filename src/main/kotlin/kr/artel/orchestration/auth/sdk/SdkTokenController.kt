package kr.artel.orchestration.auth.sdk

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인한 사용자가 자기 SDK 토큰을 곧장 받아 가는 경로.
 *
 * `/api/auth/sdk/codes` + `/api/auth/sdk/token` 왕복은 사람이 브라우저 창을 여는 단계를 전제한다.
 * CLI 가 게임을 띄우면서 SDK 토큰을 쥐여 주려면 그 단계가 없어야 해서, 같은 토큰을 왕복 없이 내는
 * 자리를 하나 더 둔다. 여기서는 코드 저장소도 PKCE 도 지나지 않는다 — 자격증명은 이미 인증된
 * 이 요청 자체다.
 *
 * 나오는 토큰은 [SdkTokenIssuer] 가 만든다. 즉 loopback 으로 받은 토큰과 audience·수명·refresh
 * 경로가 같은 정도가 아니라 같은 코드가 낸 같은 값이고, SDK 쪽에는 바뀌는 것이 없다.
 *
 * 경로가 `/api/sdk` 아래가 아닌 이유는 그 체인이 `aud=artel-sdk` 만 받기 때문이다. 이 요청은 바로
 * 그 토큰을 아직 못 가진 쪽이 부른다. `/api/auth` 아래라 `SecurityConfig` 의 `.authenticated()` 가
 * 이미 덮고, 쿠키 세션과 `artel_` bearer 가 둘 다 그 체인에서 인증된다.
 *
 * 이름이 `/api/auth/sdk/tokens` 가 아닌 것은 `/api/auth/sdk/token` 과 글자 하나 차이가 되기
 * 때문이다. 그쪽은 permitAll 이고 이쪽은 authenticated 라, 두 경로를 헷갈리는 것이 곧 인증 규칙을
 * 헷갈리는 것이 된다. `/api/auth/cli-tokens` 와 나란한 이름이기도 하다.
 */
@Tag(name = "SDK token", description = "로그인한 사용자의 SDK 토큰 발급")
@RestController
@RequestMapping("/api/auth/sdk-tokens")
class SdkTokenController(
    private val sdkTokenIssuer: SdkTokenIssuer,
    private val sessionUserResolver: SessionUserResolver
) {
    /**
     * 부르는 사람 자신의 SDK 토큰을 낸다. 요청 본문이 없다 — 누구의 토큰인지는 자격증명이 정하고,
     * 수명과 범위는 고를 수 있는 값이 아니다.
     *
     * 201 이 아니라 200 이다. 남는 행이 없는 상태 없는 JWT 라 만들어지는 리소스가 없고, 같은 토큰을
     * 내는 `/api/auth/sdk/token` 이 이미 200 이다.
     *
     * ### CLI 토큰으로도 부를 수 있다
     *
     * `POST /api/auth/cli-tokens` 는 CLI 토큰으로 부르면 403(`cli_token_cannot_issue`)이다. 여기는
     * 반대로 허용한다. 두 판단이 다르므로 왜 다른지 적는다.
     *
     * **두 자격증명이 여는 범위가 다르다.** CLI 토큰은 브라우저 세션과 같은 경로 집합을 연다 —
     * 프로젝트, QA 실행, 계정 설정, CLI 토큰 목록과 폐기까지 전부다. SDK 토큰이 여는 것은
     * `/api/sdk` 아래 체인 하나이고, 지금 그 아래에 있는 것은 인스턴스 등록, QA 캡처 업로드 티켓,
     * 근거 문서 업로드 티켓, 그리고 이름과 id 만 내는 프로젝트 목록 넷뿐이다. `aud` 가 달라 SDK
     * 토큰으로 브라우저 API 를 부를 수는 없다.
     *
     * 정확히 말하면 부를 수 있는 경로가 줄기만 하는 것은 아니다. CLI 토큰은 `aud` 때문에
     * `/api/sdk` 아래를 못 부르므로, 그 넷은 이 발급으로 새로 열린다. 그래도 좁은 쪽이라고 보는
     * 이유는 그 넷이 모두 그 사용자가 참여한 프로젝트 안에서 게임 런타임이 하는 일이고, 그
     * 사용자가 대시보드에서 이미 할 수 있는 일의 부분집합이기 때문이다. 게다가 이 endpoint 가
     * 없어도 같은 사람이 브라우저 loopback 으로 같은 토큰을 받을 수 있다. 이 경로가 더하는 것은
     * 권한이 아니라 "창을 열지 않아도 된다"는 것뿐이다.
     *
     * **CLI 토큰이 CLI 토큰을 못 만드는 이유는 범위가 아니라 폐기다.** 같은 범위의 후계자를
     * 찍어낼 수 있으면 원본을 폐기해도 접근이 남아, 목록 화면의 폐기 버튼이 거짓말이 된다.
     *
     * 그 폐기 문제가 여기서 완전히 사라지지는 않는다. SDK 토큰은 상태 없는 JWT 라 개별 폐기가
     * 없고, 폐기된 CLI 토큰으로 그 전에 받아 둔 SDK 토큰은 최대 30일(refresh 로 90일)까지 살아
     * 있는다. 그래도 받아들이는 것은 남는 것이 위의 좁은 경로 집합뿐이고 수명에 상한이 있기
     * 때문이다. 폐기가 SDK 토큰까지 즉시 끊어야 한다면 SDK 토큰에 발급 출처를 싣고 상태를 두어야
     * 하며, 그것은 이 작업의 범위가 아니다.
     *
     * ### SDK 토큰 자신을 내밀면 401 이다
     *
     * 이 경로는 브라우저 체인에 있고 그 체인의 디코더는 `aud=artel-home` 만 받는다. 그래서 SDK
     * 토큰으로 SDK 토큰을 무한히 이어 발급하는 길은 코드를 더하지 않아도 막혀 있다. 브라우저
     * refresh 토큰도 같은 이유로 401 이다.
     *
     * principal 을 non-null 로 두면 인증 실패가 401 이 아니라 NPE(500)로 새어 나간다.
     * `/api/auth/me` 와 같은 이유로 nullable 로 받고 직접 401 을 던진다.
     */
    @Operation(
        summary = "SDK 토큰 발급",
        description = "로그인한 사용자에게 자기 SDK 토큰을 낸다. 쿠키 세션과 CLI 토큰으로 부를 수 있다."
    )
    @PostMapping
    suspend fun mint(@AuthenticationPrincipal jwt: Jwt?): SdkTokenResponse {
        val session = jwt?.let(sessionUserResolver::resolve) ?: throw UnauthorizedException()
        // 서명이나 행은 유효해도 그 사용자가 지워졌을 수 있다. 그 경우는 세션이 아니므로 401 이다.
        return sdkTokenIssuer.issueFor(session.userId) ?: throw UnauthorizedException()
    }
}
