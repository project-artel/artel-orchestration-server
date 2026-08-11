package kr.artel.orchestration.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
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
}
