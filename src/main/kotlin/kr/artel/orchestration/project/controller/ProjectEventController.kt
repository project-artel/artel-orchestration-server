package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.event.ProjectChangeEvent
import kr.artel.orchestration.project.event.ProjectChangeService
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Project Events", description = "프로젝트 데이터 변경 알림")
@RestController
@RequestMapping("/api/projects/{projectId}/events")
class ProjectEventController(
    private val projectChangeService: ProjectChangeService,
) {

    @Operation(
        summary = "프로젝트 변경 스트림",
        description = "프로젝트의 게임 인스턴스, 빌드, macro, issue, knowledge, test case, 사용량이 바뀌면 " +
            "`changed` 프레임에 topic 만 실어 보낸다. 구독 직후 `ready` 프레임을 한 번 보내며, client 는 " +
            "이때 모든 목록을 다시 읽는다. 접근할 수 없는 프로젝트는 stream 이 열리지 않고 404 로 끝난다."
    )
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): Flow<ServerSentEvent<ProjectChangeEvent>> =
        projectChangeService.events(appUserId, projectId)
}
