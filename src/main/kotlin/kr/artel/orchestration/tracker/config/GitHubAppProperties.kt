package kr.artel.orchestration.tracker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * GitHub App 자격증명과 endpoint.
 *
 * **비어 있어도 기동은 성공한다.** `init { require(...) }` 로 값을 강제하지 않는 이유가 그것이다 —
 * App 을 아직 등록하지 않은 환경(로컬·테스트·기존 배포)에서 서버가 아예 뜨지 않으면 tracker 를 쓰지
 * 않는 QA 경로까지 함께 죽는다. 대신 연동 endpoint 가 불릴 때
 * [kr.artel.orchestration.tracker.client.TrackerNotConfiguredException] 으로 명확히 거절한다.
 *
 * ⚠️ [privateKey] 는 비밀이다. DB 에 쓰지 않고, 로그에 남기지 않는다.
 */
@ConfigurationProperties("artel.tracker.github")
data class GitHubAppProperties(
    /** GitHub App 설정 화면의 App ID(숫자). App JWT 의 `iss` 가 된다. */
    val appId: String = "",
    /** App 의 URL slug. 설치 주소 `https://github.com/apps/<slug>/installations/new` 를 만든다. */
    val appSlug: String = "",
    /**
     * PKCS#8 PEM. 환경변수 한 줄로 실을 수 있도록 `\n` 이스케이프를 실제 개행으로 되돌려 읽는다.
     * 파싱은 기동이 아니라 첫 사용에서 한 번만 한다 — 오타 하나가 서버를 못 뜨게 하면 안 된다.
     */
    val privateKey: String = "",
    /**
     * App 설정 화면의 Client ID / Client secret.
     *
     * 이슈를 쓰는 데는 필요 없다. **`installation_id` 의 소유를 확인하는 데** 쓴다 — 설치 후 콜백이
     * 싣고 오는 `code` 를 user-to-server token 으로 바꿔, 그 사람이 접근할 수 있는 installation
     * 목록에 그 id 가 있는지 본다. 이 확인이 없으면 남의 installation 을 자기 프로젝트에 붙여
     * private 저장소 목록을 읽을 수 있다.
     */
    val clientId: String = "",
    val clientSecret: String = "",
    val apiBaseUrl: String = "https://api.github.com",
    val webBaseUrl: String = "https://github.com",
    /**
     * installation access token 을 만료 얼마 전에 다시 발급받을지. GitHub 이 주는 수명은 한 시간이다.
     * 0 이면 만료 순간에 걸친 요청이 401 로 떨어진다.
     */
    val tokenRefreshSkew: Duration = Duration.ofMinutes(5),
    /**
     * GitHub 호출 하나의 상한. ⚠️ [TrackerProperties.claimStaleAfter] 보다 훨씬 작아야 한다 —
     * 그 관계가 깨지면 `claim` 이 두 번 성립해 외부 이슈가 둘 생긴다.
     */
    val requestTimeout: Duration = Duration.ofSeconds(15)
) {
    /** App 을 쓸 수 있는 설정이 갖춰졌는지. 아니면 연동 endpoint 가 503 으로 거절한다. */
    val configured: Boolean
        get() = appId.isNotBlank() && appSlug.isNotBlank() && privateKey.isNotBlank() &&
            clientId.isNotBlank() && clientSecret.isNotBlank()

    /** 환경변수로 실려 온 `\n` 이스케이프를 되돌린 PEM 원문. */
    val privateKeyPem: String
        get() = privateKey.replace("\\n", "\n")
}
