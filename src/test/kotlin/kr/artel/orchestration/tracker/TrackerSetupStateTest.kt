package kr.artel.orchestration.tracker

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.service.TrackerSetupStateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * 설치 `state` 의 서명(ARTEL-671).
 *
 * 서명이 없으면 아무나 자기 브라우저로 설치를 시작하면서 남의 projectId 를 적어 넣어, 그 프로젝트에
 * 자기 `installation` 을 붙일 수 있다. 그 문장이 이 스위트가 지키는 전부다.
 */
@ActiveProfiles("test")
@SpringBootTest
class TrackerSetupStateTest {

    @Autowired private lateinit var stateService: TrackerSetupStateService
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var properties: AuthProperties

    @Test
    fun `carries the project the user and the provider through a round trip`(): Unit = runBlocking {
        val state = stateService.issue(42L, 7L, TrackerProvider.GITHUB)

        val verified = stateService.verify(state)!!

        assertThat(verified.projectId).isEqualTo(42L)
        assertThat(verified.userId).isEqualTo(7L)
        assertThat(verified.provider).isEqualTo(TrackerProvider.GITHUB)
    }

    @Test
    fun `rejects a state that is missing forged or truncated`(): Unit = runBlocking {
        val state = stateService.issue(42L, 7L, TrackerProvider.GITHUB)

        assertThat(stateService.verify(null)).isNull()
        assertThat(stateService.verify("")).isNull()
        assertThat(stateService.verify("42")).isNull()
        assertThat(stateService.verify(state.dropLast(4))).isNull()
        // 서명 부분만 갈아 끼운 토큰. payload 는 그대로라 파싱은 되고 검증에서만 떨어져야 한다.
        assertThat(stateService.verify(state.substringBeforeLast('.') + ".AAAA")).isNull()
    }

    @Test
    fun `rejects a browser session token presented as state`(): Unit = runBlocking {
        // audience 를 나누지 않았다면 쿠키를 그대로 state 자리에 내밀 수 있다.
        val sessionToken = jwtService.issue(
            AuthenticatedUser(
                userId = "7",
                provider = "github",
                login = "octocat",
                displayName = "octocat",
                avatarUrl = null
            )
        )
        assertThat(properties.trackerSetupAudience).isNotEqualTo(properties.audience)
        assertThat(stateService.verify(sessionToken)).isNull()
    }
}
