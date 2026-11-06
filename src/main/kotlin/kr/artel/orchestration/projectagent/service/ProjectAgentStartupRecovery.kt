package kr.artel.orchestration.projectagent.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 기동이 끝나고 요청을 받기 전에 `RUNNING` 으로 남은 대화를 `ERROR` 로 돌린다(ARTEL-1127). step 호출은 프로세스 안의 coroutine 이라
 * 프로세스가 죽으면 같이 사라지고, 그 대화는 아무도 `RUNNING` 에서 꺼내 주지 않는다.
 *
 * 서버를 둘 이상 띄우면 다른 서버가 지금 돌리는 step 까지 `ERROR` 로 돌린다. 지금은 서버가 하나라 허용한다.
 */
@Component
class ProjectAgentStartupRecovery(private val service: ProjectAgentService) {
    private val logger = LoggerFactory.getLogger(ProjectAgentStartupRecovery::class.java)

    @EventListener(ApplicationStartedEvent::class)
    fun recover() {
        runBlocking {
            try {
                val recovered = service.recoverInterrupted()
                if (recovered > 0) logger.warn("중단된 project agent 대화 {}건을 ERROR 로 돌렸다", recovered)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 복구가 깨졌다고 서버 기동까지 실패시키지 않는다.
                logger.error("중단된 project agent 대화를 복구하지 못했다", error)
            }
        }
    }
}
