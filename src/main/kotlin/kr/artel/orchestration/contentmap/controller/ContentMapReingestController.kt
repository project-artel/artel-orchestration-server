package kr.artel.orchestration.contentmap.controller

import kotlinx.coroutines.flow.firstOrNull
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **이미 받은 근거 문서를 다시 적재한다.**
 *
 * 적재는 SDK 가 보내는 스캔 결과 프레임이 와야 돈다. 그래서 적재기가 문서에서 더 많은 칸을 옮기게
 * 고쳐도, 이미 있는 지도는 게임을 다시 스캔하기 전까지 그 칸이 비어 있다. 문서는 스토리지에 그대로
 * 있으니 그것을 다시 읽으면 된다.
 *
 * 다시 적재해도 안전하다. 기능은 키로 덮어쓰고 문서가 더는 말하지 않는 것만 내리며, 케이스도 함께
 * 다시 앉는다([ContentMapIngestService.ingest]). 테스트 케이스만 다시 뽑는 쪽은
 * `MapTestCaseRegenerateController` 다.
 *
 * 내부 포트에만 뜬다(`/internal` 아래).
 */
@RestController
@RequestMapping("/internal/content-maps")
class ContentMapReingestController(
    private val documents: ContentMapDocumentRepository,
    private val ingest: ContentMapIngestService,
) {

    /** 그 지도가 가장 최근에 받은 문서를 다시 적재한다. */
    @PostMapping("/{contentMapId}/reingest")
    suspend fun reingest(@PathVariable contentMapId: Long): Map<String, String> {
        val document = documents.findByContentMapIdOrderByReceivedAtDesc(contentMapId).firstOrNull()
            ?: throw NotFoundException("다시 적재할 근거 문서가 없습니다.")
        return mapOf("contentMapId" to contentMapId.toString(), "result" to ingest.ingest(document).toString())
    }
}
