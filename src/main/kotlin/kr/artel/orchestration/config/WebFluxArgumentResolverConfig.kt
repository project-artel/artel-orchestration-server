package kr.artel.orchestration.config

import kr.artel.orchestration.auth.web.CurrentUserIdArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer

/**
 * 컨트롤러 파라미터를 채우는 커스텀 리졸버를 등록한다.
 *
 * `@EnableWebFlux`가 아니라 `WebFluxConfigurer` 빈이므로 Boot의 자동 설정은 그대로 살아 있다.
 * 커스텀 리졸버는 내장 리졸버 **뒤에** 붙으므로 `@PathVariable` 같은 기본 어노테이션의
 * 해석을 가로채지 않는다.
 *
 * 이 앱은 `HttpHandler` 체인을 둘 조립하지만(`InternalApiConfig`) 둘 다 같은
 * `DispatcherHandler`를 쓴다 — 여기 한 번 등록하면 두 포트에 모두 적용된다.
 */
@Configuration
class WebFluxArgumentResolverConfig(
    private val currentUserIdArgumentResolver: CurrentUserIdArgumentResolver
) : WebFluxConfigurer {

    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(currentUserIdArgumentResolver)
    }
}
