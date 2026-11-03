package kr.artel.orchestration.testcase.transfer

import kr.artel.orchestration.auth.web.CurrentUserId
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * TC 파일 내보내기 · 가져오기(ARTEL-1327).
 *
 * 가져오기는 파일 내용을 요청 본문 그대로 받는다(multipart 아님). 형식은 내용을 보고 정하고, `fileName` 은 오류 문구에만 쓴다.
 * 미리 보기와 저장은 같은 파일 · 같은 계획이다 — 저장은 미리 보기에서 보인 줄 중 새로 · 고침만 넣는다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-cases")
class CaseFileController(private val caseFiles: CaseFileService) {

    /** TC 를 xlsx · csv 로 내려준다. `testCaseIds` 를 주면 화면에 보이는 TC 만, 그 순서로. */
    @PostMapping("/exports")
    suspend fun exportTestCases(
        @PathVariable projectId: Long,
        @RequestBody request: CaseFileExportRequest,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<ByteArray> = fileResponse(caseFiles.export(projectId, appUserId, request))

    /** 가져오기 양식. 엑셀은 머리글 필터 · 종류 고르기 목록 · 작성법 시트가 든 표, CSV 는 머리글이다. 둘 다 예시 한 줄이 있고, 그대로 두면 가져올 때 건너뛴다. */
    @GetMapping("/imports/template")
    suspend fun downloadTestCaseImportTemplate(
        @PathVariable projectId: Long,
        @RequestParam(required = false) format: String?,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<ByteArray> = fileResponse(caseFiles.template(projectId, appUserId, format))

    private fun fileResponse(download: CaseFileService.Download): ResponseEntity<ByteArray> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, download.format.contentType)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.fileName, StandardCharsets.UTF_8).build().toString(),
            )
            .body(download.bytes)
    }

    /** 파일을 읽어 줄마다 새로 · 고침 · 그대로 · 못 넣음을 정한다. 아무것도 저장하지 않는다. */
    @PostMapping("/imports/preview", consumes = [MediaType.ALL_VALUE])
    suspend fun previewTestCaseImport(
        @PathVariable projectId: Long,
        @RequestParam(required = false) gameBuildId: String?,
        @RequestParam(required = false) fileName: String?,
        @RequestBody file: ByteArray,
        @CurrentUserId appUserId: Long,
    ): CaseImportPreview = caseFiles.preview(projectId, appUserId, gameBuildId, fileName, file)

    /** 미리 보기와 같은 계획으로 새로 · 고침 줄을 넣는다. `gameBuildId=unversioned` 면 버전 없이 넣는다. */
    @PostMapping("/imports", consumes = [MediaType.ALL_VALUE])
    suspend fun importTestCases(
        @PathVariable projectId: Long,
        @RequestParam(required = false) gameBuildId: String?,
        @RequestParam(required = false) fileName: String?,
        @RequestBody file: ByteArray,
        @CurrentUserId appUserId: Long,
    ): CaseImportResult = caseFiles.import(projectId, appUserId, gameBuildId, fileName, file)
}
