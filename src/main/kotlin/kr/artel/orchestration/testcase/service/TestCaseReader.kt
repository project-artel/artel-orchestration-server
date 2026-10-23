package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.dto.CaseListFields
import kr.artel.orchestration.testcase.dto.VersionListFields
import kr.artel.orchestration.testcase.dto.originLabel
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.springframework.stereotype.Service

/**
 * 어느 버전의 내용으로 TC 를 읽는가(ARTEL-1069).
 *
 * 케이스를 읽는 자리마다 이 값 하나를 넘긴다. TR 화면 · 저작은 TR 의 버전, QA 판정은 켜진 게임의 버전, 버전을 따르지
 * 않는 화면은 [Latest] 다.
 */
sealed interface CaseScope {
    /** 정체(`test_case`) 그대로. 정체는 가장 최근 버전을 비추므로 "최신" 이다. */
    data object Latest : CaseScope

    /** [gameBuildId] 버전의 몫. 그 버전에 몫이 없는 케이스는 그 버전에 없다. */
    data class Version(val gameBuildId: Long) : CaseScope

    companion object {
        /** 버전이 없으면(버전 없는 TR, 빌드를 모르는 실행) [Latest] 다. */
        fun of(gameBuildId: Long?): CaseScope = gameBuildId?.let(::Version) ?: Latest
    }
}

/**
 * TC 를 [CaseScope] 에 맞춰 읽는 **한 자리**(ARTEL-1069).
 *
 * 버전 몫을 정체와 같은 모양([TestCaseEntity], id 는 정체 id)으로 비춰 돌려주므로, 받는 쪽은 버전을 몰라도 된다.
 * 비추는 규칙은 정체를 최신 버전에 맞출 때(`TestCaseVersionRepository.syncIdentities`)와 같다:
 * - 판정 상태는 늘 그 버전의 것이다.
 * - 내용은 지도가 낸 케이스만 그 버전의 것이다. 사람이 쓴 케이스는 사람이 고친 정체가 원본이고, 버전 몫은 판정을
 *   받던 때의 스냅샷일 뿐이다.
 */
@Service
class TestCaseReader(
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val gameBuilds: GameBuildRepository,
) {
    /** 프로젝트의 케이스, id 오름차순. [CaseScope.Version] 이면 그 버전에 든 것만 그 버전 내용으로. */
    suspend fun inScope(projectId: Long, scope: CaseScope): List<TestCaseEntity> {
        val identities = testCases.findByProjectIdOrderByIdAsc(projectId).toList()
        if (scope !is CaseScope.Version) return identities
        val rows = rowsOf(scope.gameBuildId)
        return identities.mapNotNull { identity -> rows[identity.id]?.let { project(identity, it) } }
    }

    /** 케이스 여럿. [CaseScope.Version] 이면 그 버전에 든 것만 돌려준다 — 없는 id 는 빠진다. */
    suspend fun byIds(ids: Collection<Long>, scope: CaseScope): Map<Long, TestCaseEntity> {
        if (ids.isEmpty()) return emptyMap()
        val identities = testCases.findAllById(ids).toList()
        if (scope !is CaseScope.Version) return identities.associateBy { requireNotNull(it.id) }
        val rows = rowsOf(scope.gameBuildId)
        return identities.mapNotNull { identity -> rows[identity.id]?.let { requireNotNull(identity.id) to project(identity, it) } }
            .toMap()
    }

    /**
     * [identities] 를 [scope] 로 비춘다. 그 버전에 몫이 없는 케이스는 정체 그대로 둔다 — 그런 케이스를 가리키는 옛
     * 시나리오의 사실이 사라지지 않게 하는 저작용이다. 그 버전에 든 것만 원하면 [inScope] · [byIds] 다.
     */
    suspend fun overlay(identities: List<TestCaseEntity>, scope: CaseScope): List<TestCaseEntity> {
        if (scope !is CaseScope.Version || identities.isEmpty()) return identities
        val rows = rowsOf(scope.gameBuildId)
        return identities.map { identity -> rows[identity.id]?.let { project(identity, it) } ?: identity }
    }

    /** [overlay] 의 한 건판. */
    suspend fun overlay(identity: TestCaseEntity, scope: CaseScope): TestCaseEntity {
        if (scope !is CaseScope.Version) return identity
        return versions.findByTestCaseIdAndGameBuildId(requireNotNull(identity.id), scope.gameBuildId)
            ?.let { project(identity, it) } ?: identity
    }

    /**
     * [gameBuildId] 버전에 든 케이스 id. 그 버전에서 `BROKEN` 인 몫은 뺀다 — 깨진 케이스는 저작에 보내지 않고 커버리지
     * 기준에도 없다.
     */
    suspend fun caseIds(gameBuildId: Long): Set<Long> =
        versions.findByGameBuildId(gameBuildId).toList()
            .filter { it.verificationStatus != VerificationStatus.BROKEN.name }
            .map { it.testCaseId }
            .toSet()

    /**
     * 켜진 build [gameBuildId] 의 판정이 속할 버전. build 행이면 그 행이다. editor 행은 그 editor 버전에서 TC 를 만들었으면
     * 그 행이고(ARTEL-1180), 아니면 예전처럼 같은 version 의 게임 빌드다(ARTEL-1014). 빌드를 모르거나 둘 다 없으면 null 이다.
     */
    suspend fun judgedBuildId(gameBuildId: Long?): Long? {
        val build = gameBuildId?.let { gameBuilds.findById(it) } ?: return null
        if (!build.isEditor) return build.id
        if (versions.existsByGameBuildId(requireNotNull(build.id))) return build.id
        return gameBuilds.findNonEditorByVersion(build.projectId, build.version)?.id
    }

    /** 버전 몫 하나를 정체 모양으로. 규칙은 클래스 설명에 적었다. */
    fun project(
        identity: TestCaseEntity,
        version: TestCaseVersionEntity,
        /** 부르는 쪽이 이미 본 출처. 목록처럼 줄마다 부를 때 metadata 를 두 번 읽지 않게 넘긴다. */
        isMap: Boolean = identity.originLabel() == MAP,
    ): TestCaseEntity {
        val base = identity.copy(verificationStatus = version.verificationStatus)
        if (!isMap) return base
        return base.copy(
            scene = version.scene,
            step = version.step,
            precondition = version.precondition,
            condition = version.condition,
            expectedValue = version.expectedValue,
            expectedItems = version.expectedItems,
            // 판정 구조도 그 버전 것이다. 문장만 버전을 따르고 구조가 최신이면 판정이 다른 문장의 항목을 본다.
            expectedChecks = version.expectedChecks,
            status = version.status,
            capabilityKey = version.capabilityKey,
            metadata = version.metadata,
        )
    }

    /**
     * 목록용 [project](ARTEL-1212). 칸만 읽은 행에 같은 규칙을 쓴다: 지도 케이스면 그 버전 몫의 내용, 아니면 사람이 고친
     * 정체의 내용. [project] 의 규칙을 바꾸면 여기도 같이 바꾼다(TestCaseVersionLibraryIntegrationTest 가 두 길의 출력을 본다).
     */
    fun projectListRow(case: CaseListFields, version: VersionListFields): ShownContent =
        if (case.fromMap) {
            ShownContent(version.scene, version.step, version.precondition, version.expectedValue, version.status)
        } else {
            ShownContent(case.scene, case.step, case.precondition, case.expectedValue, case.status)
        }

    private suspend fun rowsOf(gameBuildId: Long): Map<Long, TestCaseVersionEntity> =
        versions.findByGameBuildId(gameBuildId).toList().associateBy { it.testCaseId }

    private companion object {
        const val MAP = "MAP"
    }
}

/** 목록 한 줄에 보일 내용(ARTEL-1212). */
data class ShownContent(
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
)
