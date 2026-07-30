package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.testcase.dto.TestCaseSpecDownloadResponse
import kr.artel.orchestration.testcase.service.TestCaseSpecService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 기능 테스트 명세(XLSX) 다운로드 REST(외부/인증, 코루틴).
 *
 * 파일 바이트를 이 서버가 흘려보내지 않고 **단기 서명 URL만 발급**한다. 기획서 다운로드와 같은
 * 방식이다 — 수백 KB짜리 파일을 매번 애플리케이션이 중계하면 이벤트 루프만 붙잡고, S3가 직접
 * 내려주는 것보다 나은 점이 없다.
 *
 * 명세를 만드는 쪽(Agent → CSV 수신)은 이 컨트롤러에 없다. 전송 방식이 확정되면 그 수신부가
 * [TestCaseSpecService.ingest]를 부른다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-case-spec")
class TestCaseSpecController(
    private val service: TestCaseSpecService,
    private val sessionUserResolver: SessionUserResolver
) {

    /** 아직 받은 명세가 없거나 프로젝트 참여자가 아니면 404. */
    @GetMapping("/download")
    suspend fun download(
        @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestCaseSpecDownloadResponse> =
        service.downloadTicket(projectId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()
}
