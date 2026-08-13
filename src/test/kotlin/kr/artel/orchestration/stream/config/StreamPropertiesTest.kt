package kr.artel.orchestration.stream.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource

/**
 * 임대 기본값은 브라우저가 갱신을 몇 번까지 놓쳐도 되는지를 정한다.
 *
 * 숨겨진 탭의 갱신은 1분에 1회까지 조여지므로, 그 주기를 못 넘는 값은 정상 시청을 매번 잘라낸다.
 * 이전 기본값 15초가 정확히 그 상태였고(ARTEL-388), 증상이 설정 오류가 아니라 네트워크 장애처럼
 * 보여 진단에 시간을 썼다. 그래서 "60을 넘는다"는 사실은 주석이 아니라 테스트가 붙들어야 한다.
 */
class StreamPropertiesTest {

    @Test
    fun `the default lease outlasts the renew interval of a throttled background tab`() {
        val properties = StreamProperties()

        assertThat(properties.leaseSeconds).isEqualTo(90)
        // 조여진 탭은 1분에 한 번만 갱신을 보낸다. 그 주기를 못 넘으면 유실 허용 폭이 0이다.
        assertThat(properties.leaseSeconds).isGreaterThan(THROTTLED_RENEW_SECONDS)
        assertThat(properties.lease.seconds).isEqualTo(properties.leaseSeconds)
    }

    /**
     * 이 거절은 의도한 것이다. `.env`에 옛 기본값을 박아 둔 배포는 부팅에 실패한다.
     *
     * 그 값으로 부팅을 허용하면 새 기본값이 그 배포에 영영 닿지 않고, 15초마다 끊기는 화면이
     * 설정 오류가 아니라 네트워크 장애의 모습으로 남는다. 조용히 고장난 채 뜨는 것보다 배포
     * 시점에 시끄럽게 실패하는 편이 낫다 — 고치는 것은 `.env` 한 줄이다.
     */
    @Test
    fun `the old default is now rejected outright`() {
        assertThatIllegalArgumentException()
            .isThrownBy { StreamProperties(leaseSeconds = 15) }
            .withMessageContaining("artel.stream.lease-seconds")
    }

    @Test
    fun `the floor sits just above the throttled renew interval`() {
        assertThatIllegalArgumentException()
            .isThrownBy { StreamProperties(leaseSeconds = THROTTLED_RENEW_SECONDS) }

        assertThat(StreamProperties(leaseSeconds = THROTTLED_RENEW_SECONDS + 1).leaseSeconds)
            .isEqualTo(61)
    }

    /**
     * 프로덕션에 실제로 적용되는 기본값은 `application.yml` 쪽이다.
     *
     * 그 키가 항상 존재하므로 Spring이 바인딩하는 값은 언제나 파일에서 나오고, 위 data class의
     * 기본값은 직접 생성할 때만 보이는 fallback이다. 둘이 갈라지면 이 파일의 다른 테스트는 90을
     * 보고 통과하는데 서버는 옛 값으로 뜬다 — 이 이슈가 고치려는 상황이 초록 불 아래 그대로 남는다.
     *
     * 읽는 방법은 Spring이 프로덕션에서 쓰는 경로 그대로이고, 프로퍼티 소스를 파일 하나로 격리한다.
     * `StandardEnvironment`를 쓰면 실행 셸의 `ARTEL_STREAM_LEASE_SECONDS`가 자리를 채워, 정작
     * 확인하려던 파일 안의 기본값을 못 보고 지나간다.
     */
    @Test
    fun `the yaml default matches the one compiled into the properties class`() {
        val yaml = YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .single()

        val resolver = PropertySourcesPropertyResolver(MutablePropertySources().apply { addFirst(yaml) })

        assertThat(resolver.getProperty(LEASE_PROPERTY, Long::class.java))
            .isEqualTo(StreamProperties().leaseSeconds)
    }

    private companion object {
        /** 브라우저가 숨겨진 탭의 타이머를 조일 때의 갱신 주기. 임대 하한이 여기서 나온다. */
        const val THROTTLED_RENEW_SECONDS = 60L
        const val LEASE_PROPERTY = "artel.stream.lease-seconds"
    }
}
