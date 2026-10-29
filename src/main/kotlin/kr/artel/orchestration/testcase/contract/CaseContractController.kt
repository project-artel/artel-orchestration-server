package kr.artel.orchestration.testcase.contract

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 계약 정리를 지금 돌린다. 저작이 시작될 때도 저절로 돌지만, 일괄 가져오기 직후에 미리 돌려 두면
 * 첫 저작이 기다리지 않는다. 빠진 것과 문구가 바뀐 것만 보낸다.
 */
@RestController
class CaseContractController(
    private val caseContracts: CaseContractService,
    private val projectAccess: ProjectAccessService,
) {
    @PostMapping("/api/projects/{projectId}/case-contracts")
    suspend fun refreshCaseContracts(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<CaseContractRefresh> =
        if (!projectAccess.isMember(projectId, appUserId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok(caseContracts.refresh(projectId))
}
