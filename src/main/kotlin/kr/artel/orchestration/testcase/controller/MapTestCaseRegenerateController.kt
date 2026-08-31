package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **이미 적재된 지도로 케이스를 다시 뽑는다**(ARTEL-684).
 *
 * 케이스는 근거 문서가 적재될 때 함께 앉는다(`ContentMapIngestService`). 그런데 적재는 SDK 가
 * 보내는 스캔 결과 프레임이 와야 돌고, 그 프레임은 유니티가 만든다. 그래서 **생성기를 고쳐도
 * 지도를 다시 적재하지 않으면 확인할 방법이 없다** — 게임을 다시 켜야 한다.
 *
 * 지도는 그대로다. 바뀐 것은 생성기뿐이다. 그러니 지도를 다시 읽어 케이스만 다시 앉히면 된다.
 *
 * 내부 포트에만 뜬다(`/internal` 아래). 사용자 화면에서 부를 자리가 아니고, 부르면 그 프로젝트의
 * 지도 출신 케이스가 통째로 다시 쓰인다.
 */
@RestController
@RequestMapping("/internal/test-cases")
class MapTestCaseRegenerateController(
    private val writer: MapTestCaseWriter,
) {

    /** 지도 번호를 받아 그 지도의 케이스를 다시 앉힌다. 어느 프로젝트인지는 지도가 안다. */
    @PostMapping("/content-maps/{contentMapId}/regenerate")
    suspend fun regenerate(@PathVariable contentMapId: Long): Map<String, String> =
        mapOf("contentMapId" to contentMapId.toString(), "result" to writer.rewrite(contentMapId).toString())
}
