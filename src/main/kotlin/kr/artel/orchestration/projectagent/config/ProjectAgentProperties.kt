package kr.artel.orchestration.projectagent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * project agent 대화의 설정(ARTEL-1127). agent-server 주소는 [kr.artel.orchestration.settings.config.AgentServerProperties]
 * 의 `baseUrl` 을 쓴다.
 */
@ConfigurationProperties("artel.project-agent")
data class ProjectAgentProperties(
    /** `POST {baseUrl}/project-agent/step` 한 번의 응답 상한. 넘기면 대화는 ERROR 가 된다. */
    val stepTimeout: Duration = Duration.ofSeconds(120),
    /** SSE 연결이 놀 때 comment 줄을 보내는 간격. proxy 가 연결을 닫지 않게 한다. */
    val heartbeatInterval: Duration = Duration.ofSeconds(15),
    /** 사용자 입력 한 번에 이어서 실행할 수 있는 tool call 수. 채우면 다음 step 은 tool 없이 글로만 답한다. */
    val toolCallLimit: Int = 30,
)

@Configuration
@EnableConfigurationProperties(ProjectAgentProperties::class)
class ProjectAgentConfig
