package kr.artel.orchestration.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.web.reactive.WebHttpHandlerBuilderCustomizer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

/**
 * 공개 포트와 내부 포트의 경계를 만드는 곳(ARTEL-266).
 *
 * 이 앱은 `HttpHandler` 체인을 **둘** 조립한다. 둘 다 같은 `ApplicationContext`에서 나오므로
 * `DispatcherHandler`, Security 체인, `ForwardedHeaderTransformer`, `ApiExceptionHandler`가
 * 그대로 공유된다 — 나중에 누가 `WebFilter` **빈**을 추가해도 두 포트에 똑같이 적용된다.
 * 두 체인이 다른 점은 맨 앞에 끼우는 게이트 필터 하나뿐이다.
 *
 * 예외 하나: 아래 [publicApiGateCustomizer]처럼 `WebHttpHandlerBuilderCustomizer`로 넣는
 * 필터는 Boot의 `httpHandler` 빈에만 적용되므로 **공개 체인에만** 걸린다. 두 포트에 다
 * 필요한 필터라면 빈으로 등록해야 한다.
 *
 * - 공개 체인(8080): [publicApiGateCustomizer]가 내부 경로를 404로 막는 게이트를 끼운다.
 * - 내부 체인(기본 8081): [InternalApiServer]가 내부 경로 **밖**을 404로 막는 게이트를 끼운다.
 *
 * 요청의 로컬 포트를 읽어 가르는 방식은 쓰지 않는다. 그 방식은 실제 소켓이 없는 요청
 * (`@AutoConfigureWebTestClient`로 컨텍스트에 바인딩한 테스트)에서 `localAddress`가 null이라
 * "모르면 통과"(조용히 열림)와 "모르면 차단"(멀쩡한 테스트가 빨감) 중 하나를 골라야 한다.
 * 여기서는 **어느 서버가 커넥션을 받았는지**로 갈리므로 그 선택 자체가 생기지 않는다.
 */
@Configuration
@EnableConfigurationProperties(InternalApiProperties::class)
class InternalApiConfig {

    /**
     * 공개 체인에 게이트를 끼운다.
     *
     * `WebHttpHandlerBuilderCustomizer`는 Boot의 공개 확장점이며, Boot 3.3.1의
     * `HttpHandlerAutoConfiguration$AnnotationConfig.httpHandler`가 `build()` 직전에
     * 적용한다(바이트코드로 확인). **`WebTestClient.bindToApplicationContext`는 이 경로를
     * 타지 않는다** — 그래서 컨텍스트 바인딩 테스트들은 두 포트 어느 쪽도 거치지 않고
     * 예전 그대로 동작한다.
     *
     * 게이트가 Security보다 앞이라(`add(0, ...)`) 공개 포트의 내부 경로는 401이 아니라
     * 404다. 401은 "인증만 하면 있다"는 정보를 흘리지만 404는 아무것도 흘리지 않는다.
     */
    @Bean
    fun publicApiGateCustomizer(): WebHttpHandlerBuilderCustomizer =
        WebHttpHandlerBuilderCustomizer { builder ->
            builder.filters { it.add(0, prefixGate(blockWhenInternal = true)) }
        }

    /**
     * `@ConditionalOnWebApplication(REACTIVE)`이 없으면 `webEnvironment = NONE`인 테스트
     * 12종이 통째로 죽는다. `WebFluxAutoConfiguration`이 같은 조건이라 NONE 컨텍스트에는
     * `webHandler` 빈이 없고, `WebHttpHandlerBuilder.applicationContext`가
     * `NoSuchBeanDefinitionException`을 던진다.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    fun internalApiServer(
        applicationContext: ApplicationContext,
        properties: InternalApiProperties
    ): InternalApiServer = InternalApiServer(applicationContext, properties)
}

/**
 * 무인증 서버-투-서버 접두사. `SecurityConfig`의 permitAll 목록과 같은 문자열을 같은 파서로
 * 읽으므로 두 곳의 의미론이 갈리지 않는다.
 *
 * 접두사 비교에 `startsWith`를 쓰지 않는 이유는 `/internalfoo` 같은 경로가 내부로 오인되기
 * 때문이다. `PathPattern`은 경로를 세그먼트 단위로 끊는다.
 */
private val INTERNAL_API_PATTERN = PathPatternParser.defaultInstance.parse("/internal/**")

/**
 * 경로 접두사와 포트를 묶는 게이트. 두 체인이 **같은 구현을 극성만 바꿔** 쓴다 — 따로 쓰면
 * 한쪽만 고쳐지는 드리프트가 난다.
 *
 * ⚠️ **이 필터를 `@Bean`/`@Component`로 만들지 말 것.** 빈이 되는 순간
 * `WebHttpHandlerBuilder.applicationContext`가 두 체인 모두에 넣어 서로를 무력화한다
 * (공개 게이트가 내부 체인에 들어가면 내부 포트가 자기 경로를 404로 막는다). 각 체인의
 * 조립 지점에서만 붙인다.
 *
 * @param blockWhenInternal true면 내부 경로를 막는다(공개 체인), false면 내부 경로가 아닌
 *   것을 전부 막는다(내부 체인).
 */
internal fun prefixGate(blockWhenInternal: Boolean): WebFilter = WebFilter { exchange, chain ->
    val internal = INTERNAL_API_PATTERN.matches(exchange.request.path.pathWithinApplication())
    if (internal == blockWhenInternal) notFound(exchange) else chain.filter(exchange)
}

/**
 * 404 + 빈 본문. 이 포트에 그 경로가 없다는 것 말고는 아무것도 알려주지 않는다.
 *
 * `.agents/docs/error-handling.md`의 "타입 예외를 던진다" 규약에서 벗어나는 자리다.
 * `ApiExceptionHandler`는 `@RestControllerAdvice`라 `DispatcherHandler` 안에서만 돈다.
 * `WebFilter`에서 던진 `NotFoundException`은 거기 닿지 못하고 `ExceptionHandlingWebHandler`가
 * 500으로 만든다 — 경계를 지키려다 서버 오류를 내보내는 셈이 된다.
 */
private fun notFound(exchange: ServerWebExchange): Mono<Void> {
    exchange.response.statusCode = HttpStatus.NOT_FOUND
    return exchange.response.setComplete()
}
