package kr.artel.orchestration.testscenario.service

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testrun.repository.TestRunRepository
import org.springframework.stereotype.Service
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 이 저작이 따르는 TC 버전(ARTEL-1048). TR 이 고른 게임 빌드다.
 *
 * 저작 코드는 프로젝트 단위로 짜여 있고, 지도를 고르는 자리(`contentMapIdOf`)가 여러 서비스의 깊은 곳에 있다. 그 길마다
 * `runId` 를 넘기는 대신 저작의 입구 — 에이전트 세션의 도구 처리와 [ScenarioReconcileService.reconcile] — 에서 이 값을
 * 코루틴 맥락에 싣고, 지도를 고르는 자리가 읽는다. 맥락이 없거나 [gameBuildId] 가 null 이면(버전 없는 TR) 예전처럼
 * 최신 빌드를 쓴다.
 */
class AuthoringVersion(val gameBuildId: Long?) : AbstractCoroutineContextElement(AuthoringVersion) {
    companion object Key : CoroutineContext.Key<AuthoringVersion>
}

/** 지금 저작의 TC 버전. 정해지지 않았으면 null 이다. */
suspend fun authoringGameBuildId(): Long? = coroutineContext[AuthoringVersion]?.gameBuildId

@Service
class AuthoringVersionResolver(
    private val testRuns: TestRunRepository,
    private val versions: TestCaseVersionRepository,
) {
    /** TR 의 TC 버전. TR 이 없거나 버전이 없으면 `AuthoringVersion(null)` 이다. */
    suspend fun of(runId: Long): AuthoringVersion = AuthoringVersion(testRuns.findById(runId)?.gameBuildId)

    /**
     * 그 버전에 든 케이스 id. 저작에 보내는 목록 · 검색 · 미커버 · 검수가 같은 집합을 쓴다. 그 버전에서 `BROKEN` 인
     * 몫은 뺀다 — 보내는 목록이 그 버전의 판정 상태로 거르기 때문이다(ARTEL-1064).
     */
    suspend fun caseIds(gameBuildId: Long): Set<Long> =
        versions.findByGameBuildId(gameBuildId).filter { it.verificationStatus != "BROKEN" }.map { it.testCaseId }.toSet()
}
