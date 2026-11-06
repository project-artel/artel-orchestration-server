package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.testcase.service.ContentMapTestCaseService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **이미 적재된 지도로 케이스를 다시 뽑는다**(ARTEL-684).
 *
 * 생성기를 고친 뒤 게임을 다시 켜지 않고 결과를 확인하는 경로다. 지도는 그대로이고 바뀐 것은
 * 생성기뿐이니, 지도를 다시 읽어 케이스만 다시 앉히면 된다.
 *
 * 사용자 화면의 생성 버튼(`POST /api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases`,
 * ARTEL-971)과 같은 [ContentMapTestCaseService] 를 거친다 — 그 content map 의 행만 비교하고, 새 행은 그
 * content map 의 folder 에 넣는다. 다른 점은 멤버 확인이 없다는 것뿐이다.
 *
 * 내부 포트에만 뜬다(`/internal` 아래).
 */
@RestController
@RequestMapping("/internal/test-cases")
class MapTestCaseRegenerateController(
    private val service: ContentMapTestCaseService,
) {

    /** 지도 번호를 받아 그 지도의 케이스를 다시 앉힌다. 어느 프로젝트인지는 지도가 안다. */
    @PostMapping("/content-maps/{contentMapId}/regenerate")
    suspend fun regenerate(@PathVariable contentMapId: Long): Map<String, String> =
        mapOf("contentMapId" to contentMapId.toString(), "result" to service.regenerate(contentMapId).toString())
}
