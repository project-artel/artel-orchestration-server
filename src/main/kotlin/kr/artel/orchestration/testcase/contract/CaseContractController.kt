package kr.artel.orchestration.testcase.contract

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 계약 정리를 지금 돌린다. 저작이 시작될 때도 저절로 돌지만, 일괄 가져오기 직후에 미리 돌려 두면
 * 첫 저작이 기다리지 않는다. 빠진 것과 문구가 바뀐 것만 보낸다.
 */
@RestController
class CaseContractController(
    private val caseContracts: CaseContractService,
    private val caseBatch: CaseBatchService,
    private val projectAccess: ProjectAccessService,
) {
    /** 용어집 화면(ARTEL-1312): 용어마다 정한 쪽 · 확인할 이유 · 쓰는 TC 수. */
    @GetMapping("/api/projects/{projectId}/case-glossary")
    suspend fun getCaseGlossary(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<CaseGlossaryResponse> =
        if (!projectAccess.isMember(projectId, appUserId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok(caseContracts.glossaryView(projectId))

    /** 사람이 용어 하나를 고친다. 고친 칸은 사람이 정한 것이라 AI · MCP 가 덮지 않는다. */
    @PutMapping("/api/projects/{projectId}/case-glossary/terms")
    suspend fun editCaseGlossaryTerm(
        @PathVariable projectId: Long,
        @RequestBody request: GlossaryTermEditRequest,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<GlossaryTermView> =
        if (!projectAccess.isMember(projectId, appUserId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok(caseContracts.editTerm(projectId, request))

    /** 글로 쓴 TC 와 그 계약, 용어집(MCP 의 `list_test_cases`). */
    @GetMapping("/api/projects/{projectId}/case-contracts")
    suspend fun listCaseContracts(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<CaseContractList> =
        if (!projectAccess.isMember(projectId, appUserId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok(caseContracts.list(projectId))

    /** TC 를 계약까지 한 번에 넣거나 고친다(MCP 의 `save_test_cases`). 최대 50건. */
    @PostMapping("/api/projects/{projectId}/case-contracts/cases")
    suspend fun saveCaseContracts(
        @PathVariable projectId: Long,
        @RequestBody request: CaseBatchRequest,
        @CurrentUserId appUserId: Long,
    ): CaseBatchResponse = caseBatch.save(projectId, appUserId, request)

    @PostMapping("/api/projects/{projectId}/case-contracts")
    suspend fun refreshCaseContracts(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<CaseContractRefresh> =
        if (!projectAccess.isMember(projectId, appUserId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok(caseContracts.refresh(projectId))
}
