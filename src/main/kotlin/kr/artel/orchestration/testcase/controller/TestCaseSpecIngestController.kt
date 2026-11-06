package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.testcase.dto.TestCaseSpecIngestResult
import kr.artel.orchestration.testcase.service.TestCaseSpecService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Agent가 SDK 등록 시점에 만든 **기능 테스트 명세 JSON**를 받는 서버-투-서버 경로(코루틴).
 *
 * **엔드유저 JWT 대상이 아니다.** 게임 SDK를 등록하는 쪽에는 로그인 세션이 없고, 보내는 주체는
 * 사람이 아니라 Agent 서버다. 그래서 무인증 내부 접두사인 `/internal/` 아래에 산다.
 * 사용자용 다운로드는 이 경로가 아니라 `/api/projects/{id}/test-case-spec/download`이며,
 * 그쪽은 인증 대상이다 — 접두사가 두 신뢰 모델을 갈라놓는다.
 *
 * 본문은 명세 JSON 바이트를 그대로 받는다. 봉투 하나에 케이스 배열이 담겨 온다.
 * multipart를 쓰지 않는 이유는 문서가 **하나뿐이고** 파일명·필드명 같은 곁다리 메타데이터가
 * 필요 없기 때문이다.
 *
 * **원문은 보관하지 않는다.** 서비스가 파싱해 XLSX 한 장과 `test_case` 행들을 만들고 버린다.
 */
@RestController
@RequestMapping("/internal/test-case-spec")
class TestCaseSpecIngestController(
    private val service: TestCaseSpecService
) {

    /**
     * 명세를 적재하고 XLSX 산출물을 저장한다. 없는 프로젝트면 404, JSON이 규격 밖이면 400.
     *
     * 응답으로 반영 건수를 돌려주어 보낸 쪽이 재전송 여부를 판단할 수 있게 한다. 재전송은
     * upsert라 안전하고, 같은 `revision`이면 아예 건너뛴다(`unchanged=true`).
     */
    @PostMapping(
        "/{projectId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    suspend fun ingest(
        @PathVariable projectId: Long,
        @RequestBody body: ByteArray
    ): TestCaseSpecIngestResult {
        // 빈 본문은 파서에 넘기기 전에 막는다. 파서까지 가면 Jackson의 EOF 메시지가 나가는데,
        // 그것만 보고는 "안 보냈다"인지 "직렬화가 깨졌다"인지 알 수 없다.
        if (body.isEmpty()) throw BadRequestException("명세 본문이 비어 있습니다.")
        return service.ingest(projectId, body)
    }
}
