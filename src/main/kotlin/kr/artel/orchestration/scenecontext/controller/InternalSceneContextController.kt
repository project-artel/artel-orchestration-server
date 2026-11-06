package kr.artel.orchestration.scenecontext.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.scenecontext.dto.SceneContextResponse
import kr.artel.orchestration.scenecontext.service.SceneContextService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * agent-server 가 **런 시작에 한 번** 부르는 창구(ARTEL-611). 씬 이름을 키로 "여기서 무엇을 할 수
 * 있나"(content map 의 capability)와 "여기서만 참인 것"(`knowledge_anchor` 의 지식)을 함께 낸다.
 *
 * **브라우저 조회(`/api/projects/{id}/game-builds/{id}/content-map`, ARTEL-446)와 별개의
 * 엔드포인트다.** 겸하면 사람이 읽을 것의 요구가 프롬프트의 부피를 정하게 되고, 그 뒤로는 어느
 * 쪽도 줄일 수 없다. 이쪽은 프롬프트에 들어갈 최소한만 담는다 — 조건 트리도, `evidence` 주소도,
 * 지식 본문도 없다.
 *
 * `/internal` 아래인 것은 이것이 무인증 서버-투-서버 경로이기 때문이다(ARTEL-265). 그 접두사는
 * 내부 포트(기본 8081)에만 실리므로(ARTEL-266, `InternalApiConfig`) 공개 포트에서는 404 다.
 * `SecurityConfig` 의 permitAll 목록에 줄을 더하지 않는다 — `/internal` 하위 전부를 덮는 줄이
 * 이미 있다.
 *
 * 경로가 `/projects/{projectId}/` 를 지나므로 그 값이 빌드의 프로젝트와 맞는지 **실제로
 * 검사한다.** 무인증 내부망 전용이라는 사실은 검사를 생략할 이유가 되지 못한다 — 검사하지
 * 않으면 그 값은 장식이고, 다음 호출자는 장식을 보증으로 읽는다. 검사는
 * [SceneContextService.read] 안에 있다. 컨트롤러에 두면 다음 진입점이 빠뜨릴 수 있다.
 */
@Tag(name = "Scene Context (internal)", description = "QA agent 가 런 시작에 받는 씬별 capability·앵커 지식")
@RestController
@RequestMapping("/internal/projects/{projectId}/game-builds/{gameBuildId}/scene-context")
class InternalSceneContextController(
    private val sceneContext: SceneContextService,
) {

    /**
     * 이 빌드의 씬별 맥락을 한 번에 읽는다.
     *
     * **`evidence` 를 한 번도 올리지 않은 빌드는 404 가 아니라 200 이다.** 빌드는 존재하고, 없는 것은
     * 아직 아무도 올리지 않은 문서다. 그때 `contentMapId` 가 null 이고 `scenes` 에는 앵커가 든
     * 씬만 남거나 아무것도 남지 않는다 — 둘 다 정상 상태다. 404 는 **빌드가 없거나 경로의
     * `projectId` 가 그 빌드의 것과 다를 때**, 그리고 없는 `qaTryId` 를 줬을 때뿐이다.
     */
    @Operation(
        summary = "씬별 capability·앵커 지식 조회",
        description = "게임 빌드 하나에 대해 씬 이름을 키로 capability 와 앵커 지식을 함께 낸다. " +
            "`status` 가 `not-a-step` 인 것은 `notAStepCapabilities` 로 갈라 낸다. " +
            "지식은 id 와 요약까지만 낸다. " +
            "`contentMapId` 가 null 이면 이 빌드에 아직 `evidence` 로 만든 지도가 없다.",
    )
    // 메서드 이름이 `read` 면 ProjectContentMapController.read 와 부딪혀 생성된 OpenAPI 의
    // operationId 가 `read_1` 로 밀린다. 그 번호는 어느 쪽이 먼저 스캔됐느냐에 따라 붙으므로
    // 두 컨트롤러 사이를 오가고, 스냅샷 diff 가 이유 없이 흔들린다.
    @GetMapping
    suspend fun readSceneContext(
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
        @Parameter(
            description = "이 조회를 부른 QA 런(qa_try) id. 지식 스코프를 여기서 읽는다. " +
                "생략하면 운영 스코프다."
        )
        @RequestParam(required = false) qaTryId: Long?,
    ): SceneContextResponse =
        sceneContext.read(projectId, gameBuildId, qaTryId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
}
