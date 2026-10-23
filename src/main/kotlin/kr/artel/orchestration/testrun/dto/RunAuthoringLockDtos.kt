package kr.artel.orchestration.testrun.dto

import kr.artel.orchestration.testscenario.dto.AuthoringStage
import java.time.Instant

/** 런에서 Artel Bot 이 고치는 중이라 잠긴 시나리오들(ARTEL-1264). 비었으면 아무것도 잠기지 않았다. */
data class RunAuthoringLockResponse(
    val scenarios: List<ScenarioAuthoringLock> = emptyList(),
)

/**
 * 잠긴 시나리오 하나. 맵은 그 TS 프레임만, 편집 화면은 그 TS 를 볼 때만 잠금 화면을 띄운다.
 * [mine] 은 읽는 사람이 이 턴을 보낸 사람인가다. 본인 화면도 잠기지만 문구가 다르다.
 */
data class ScenarioAuthoringLock(
    val scenarioId: String,
    val requestedBy: String,
    val requestedByName: String,
    val mine: Boolean,
    val startedAt: Instant,
    val stage: AuthoringStage?,
)
