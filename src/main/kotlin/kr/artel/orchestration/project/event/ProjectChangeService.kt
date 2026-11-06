package kr.artel.orchestration.project.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class ProjectChangeService(
    private val projectAccessService: ProjectAccessService,
    private val streamManager: ProjectChangeStreamManager,
) {

    /**
     * 프로젝트 변경 SSE. member 가 아니면 stream 을 열지 않고 404 로 끝난다.
     *
     * 연결 직후 `ready` 를 한 번 보낸다. client 는 이것을 받으면 모든 topic 을 다시 읽는다. 연결이 끊긴
     * 동안 놓친 변경은 이것으로 메운다. 그 뒤로는 바뀔 때마다 `changed` 를 보내고, [KEEPALIVE_INTERVAL]
     * 마다 comment 를 보내서 중간 proxy 가 idle 연결을 끊지 않게 한다.
     */
    suspend fun events(userId: Long, projectId: Long): Flow<ServerSentEvent<ProjectChangeEvent>> {
        projectAccessService.requireMember(projectId, userId)
        val changes = streamManager.stream(projectId).map { topic ->
            ServerSentEvent.builder(ProjectChangeEvent(topic)).event(CHANGED).build()
        }
        val keepalive = flow {
            while (true) {
                delay(KEEPALIVE_INTERVAL.toMillis())
                emit(ServerSentEvent.builder<ProjectChangeEvent>().comment("keepalive").build())
            }
        }
        return flow {
            emit(ServerSentEvent.builder<ProjectChangeEvent>().event(READY).build())
            emitAll(merge(changes, keepalive))
        }
    }

    private companion object {
        const val READY = "ready"
        const val CHANGED = "changed"
        val KEEPALIVE_INTERVAL: Duration = Duration.ofSeconds(25)
    }
}
