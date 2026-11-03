package kr.artel.orchestration.qa.state

import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * agent-server 가 QA try 의 게임 상태를 읽는 경로(ARTEL-1296).
 *
 * try 가 도는 게임 인스턴스의 **지금** 상태를 준다. 같은 인스턴스에서 다음 try 가 시작했으면 그 뒤의 상태다 —
 * 상태는 try 가 아니라 게임의 것이다.
 *
 * `/internal/` 아래라 내부 포트에만 있고 인증이 없다(`.agents/docs/project.md` 의 신뢰 경계).
 */
@RestController
@RequestMapping("/internal/qa-tries/{qaTryId}/state")
class InternalStateSetController(
    private val tries: QaTryRepository,
    private val stateSets: StateSetRegistry,
) {

    @PostMapping
    suspend fun readQaTryState(
        @PathVariable qaTryId: Long,
        @RequestBody(required = false) spec: ObservationSpec?,
    ): StateSnapshot {
        val qaTry = tries.findById(qaTryId) ?: throw NotFoundException("QA try 를 찾을 수 없습니다.")
        return stateSets.snapshot(qaTry.gameInstanceId, spec ?: ObservationSpec())
    }
}
