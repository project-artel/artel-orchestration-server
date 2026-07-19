package kr.artel.orchestration.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
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
