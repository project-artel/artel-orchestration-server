package kr.artel.orchestration.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import kr.artel.orchestration.auth.web.CurrentUserId
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    init {
        // springdoc은 @AuthenticationPrincipal을 기본으로 무시하지만 우리가 만든 어노테이션은 모른다.
        // 등록하지 않으면 인증 엔드포인트마다 appUserId가 **필수 쿼리 파라미터**로 문서화되고,
        // 그 계약이 /v3/api-docs에서 파생되는 Insomnia 컬렉션까지 흘러간다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId::class.java)
    }

    @Bean
    fun orchestrationOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Artel Orchestration Server API")
                .description(
                    "API contract for SDK registration and agent action delivery through the Artel orchestration server."
                )
                .version("v1")
        )
        // servers를 비워두면 springdoc이 실행 중인 포트로 "Generated server url"을 채운다.
        // docs/api/openapi.json 스냅샷은 RANDOM_PORT 테스트가 뜨므로 매번 다른 포트가 박혀
        // 코드가 그대로여도 diff가 난다. 상대 경로 "/"로 고정하면 Swagger UI는 지금 보고 있는
        // origin을 그대로 쓰고, 스냅샷은 포트와 무관해진다.
        .servers(listOf(Server().url("/").description("Same origin as the request")))
}
