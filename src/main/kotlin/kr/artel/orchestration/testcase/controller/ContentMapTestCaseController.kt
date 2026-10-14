package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.ContentMapTestCaseGenerationResponse
import kr.artel.orchestration.testcase.service.ContentMapTestCaseService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * build 의 content map 에서 test case 를 생성한다(ARTEL-971). ARTEL-864 가 요청한 `/api` 아래 재생성
 * 경로이기도 하다. 상태 코드 규칙은 [ContentMapTestCaseService.generate] 에 적었다.
 *
 * `carryOver=false` 면 앞 버전의 사용자 생성 케이스를 이어 넣지 않는다. 생성할 때마다 사용자가 고르고, 주지 않으면
 * 이어 넣는다.
 */
@RestController
class ContentMapTestCaseController(
    private val service: ContentMapTestCaseService,
) {

    @PostMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases")
    suspend fun generateTestCases(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @CurrentUserId appUserId: Long,
        @RequestParam(defaultValue = "true") carryOver: Boolean,
    ): ContentMapTestCaseGenerationResponse =
        service.generate(appUserId, projectId, gameBuildId, carryOver)
}
