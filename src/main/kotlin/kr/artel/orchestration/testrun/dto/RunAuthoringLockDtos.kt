package kr.artel.orchestration.testrun.dto

import kr.artel.orchestration.testscenario.dto.AuthoringStage
import java.time.Instant

/**
 * 런이 Artel Bot 에게 잠겨 있나(ARTEL-1264). 잠겨 있으면 맵과 TS 편집 화면이 잠금 화면을 띄운다.
 * [mine] 은 읽는 사람이 이 턴을 보낸 사람인가다. 본인 화면도 잠기지만 문구가 다르다.
 */
data class RunAuthoringLockResponse(
    val locked: Boolean,
    val requestedBy: String? = null,
    val requestedByName: String? = null,
    val mine: Boolean = false,
    val startedAt: Instant? = null,
    val stage: AuthoringStage? = null,
)
