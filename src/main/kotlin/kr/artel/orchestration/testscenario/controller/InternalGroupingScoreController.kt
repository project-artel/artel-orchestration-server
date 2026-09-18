package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **실험용 채점 창구** — B 하네스(워크플로 재편 플랜 Step 0)가 묶기·순서 출력을 실제 잣대로
 * 채점하는 데 쓴다. 걷기를 하네스에 두 벌 만들지 않으려고 reconcile 의 검수-만(save=false)
 * 경로를 그대로 빌린다 — 나눔·메움·어긋남이 전부 본선과 같은 규칙으로 나온다.
 *
 * 내부 포트(8081) 전용이고, 저장하지 않으므로 여러 번 불러도 흔적은 trace 뿐이다.
 */
@RestController
@RequestMapping("/internal/experiment/authoring")
class InternalGroupingScoreController(
    private val reconcileService: ScenarioReconcileService,
) {

    data class Group(val title: String, val caseIds: List<Long>)
    data class ScoreRequest(
        val runId: Long,
        val projectId: Long,
        val appUserId: Long,
        val groups: List<Group>,
    )
    data class ScoreResponse(
        /** 걷기가 짚은 어긋남 — "제목: 어긋남 설명" 그대로. 주 잣대. */
        val contradicted: List<String>,
        /** 나눔·메움을 지난 최종 조각 수 — 파편화 신호(요청 묶음 수와 비교). */
        val checkedCount: Int,
        /** 못 메운 구간 등 알림 수. */
        val noticeCount: Int,
        /** 조각별 (제목, 스텝 수) — 1~2스텝 조각 비율을 하네스가 센다. */
        val pieces: List<List<String>>,
    )

    @PostMapping("/score")
    suspend fun score(@RequestBody request: ScoreRequest): ScoreResponse {
        val scenarios = request.groups.map { group ->
            ScenarioResult(
                title = group.title,
                steps = group.caseIds.map { caseId ->
                    ChatScenarioStep(
                        action = "케이스 $caseId 확인",
                        caseId = caseId,
                        stepSource = ScenarioStepSource.CASE,
                    )
                },
            )
        }
        // reviewed = null 이면 커버리지 검수는 건너뛴다(계약 그대로) — 여기서 재는 것은
        // 묶기·순서의 걷기 품질이지 커버리지가 아니다. 누락은 하네스가 요청 대비로 센다.
        val outcome = reconcileService.reconcile(
            request.runId, request.projectId, request.appUserId,
            scenarios, reviewed = null, save = false,
        )
        return ScoreResponse(
            contradicted = outcome.contradicted,
            checkedCount = outcome.checked.size,
            noticeCount = outcome.notices.size,
            pieces = outcome.checked.map { listOf(it.title, it.steps.size.toString()) },
        )
    }
}
