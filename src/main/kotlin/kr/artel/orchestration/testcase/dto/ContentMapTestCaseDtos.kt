package kr.artel.orchestration.testcase.dto

import io.swagger.v3.oas.annotations.media.Schema
import kr.artel.orchestration.testcase.generator.GenerationPhase
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import kr.artel.orchestration.testcase.service.TestCaseGenerationState
import kr.artel.orchestration.testcase.service.TestCaseGenerationStatus
import kr.artel.orchestration.testcase.service.VerdictCarryOverService
import java.time.Instant

/**
 * build 의 content map 에서 test case 를 생성한 결과(ARTEL-971).
 *
 * 수는 모두 이 build 의 버전 몫(`test_case_version`) 기준이다(ARTEL-1018). 케이스의 정체는 버전을 넘어 하나다.
 *
 * @property created 이 build 에 새로 생긴 버전 몫 수. @property updated 문구나 구조가 바뀌어 고친 몫 수.
 * @property deleted 이 build 에서 사라져 지운 몫 수(다른 버전에도 없으면 케이스째 지운 것 포함).
 * @property broken 어느 버전에도 남지 않았지만 시나리오가 인용해 지우지 않고 `BROKEN` 으로 둔 케이스 수.
 * @property regression 앞 버전에서 판정이 나 이번 몫을 `REGRESSION` 으로 시작한 수(ARTEL-994).
 * @property carriedOver 바로 앞 버전에 있던 사용자 생성 케이스를 이 버전에 이어 넣은 수(ARTEL-1031).
 * @property verdictsCarried 앞 버전 판정을 이어받은 몫 수(ARTEL-1180). 이어받기를 고르지 않았으면 0.
 * @property verdictsReset 남아 있던 판정을 다시 검증하도록 `REGRESSION` 으로 돌린 몫 수(ARTEL-1180).
 */
data class ContentMapTestCaseGenerationResponse(
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val broken: Int,
    val regression: Int,
    val carriedOver: Int,
    val verdictsCarried: Int = 0,
    val verdictsReset: Int = 0,
) {
    companion object {
        fun of(
            result: MapTestCaseWriter.Result,
            carriedOver: Int,
            verdicts: VerdictCarryOverService.Outcome? = null,
        ) = ContentMapTestCaseGenerationResponse(
            created = result.created,
            updated = result.updated,
            deleted = result.deleted,
            broken = result.broken,
            regression = result.regression,
            carriedOver = carriedOver,
            verdictsCarried = verdicts?.carried?.size ?: 0,
            verdictsReset = verdicts?.reset?.size ?: 0,
        )
    }
}

/**
 * 라이브러리의 "지도에서 TC 생성" 모달에 보일 버전 하나(ARTEL-1040). editor 행도 있다(ARTEL-1180).
 *
 * @property isEditor editor 행인가. 같은 version 의 build 행과 따로 있다.
 * @property mapState 지도가 어디까지 왔나. `READY` 일 때만 만들 수 있다.
 * @property generated 이미 이 빌드에서 지도로 TC 를 만들었다. 다시 만들려면 덮어쓴다(`overwrite=true`).
 * @property mapCases 이 빌드의 지도 TC 수. @property judged 그중 판정(통과 · 실패 · 재검증 필요)이 난 수.
 */
data class TestCaseGenerationCandidate(
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val isEditor: Boolean = false,
    val createdAt: java.time.Instant,
    val mapState: MapState,
    val generated: Boolean,
    val mapCases: Int = 0,
    val judged: Int = 0,
) {
    enum class MapState {
        /** 스캔된 문서가 없다. */
        NO_MAP,

        /** 문서는 올라왔지만 하나도 읽히지 않았다. */
        NOT_READ,

        /** 읽힌 문서가 있다. */
        READY,
    }
}

data class TestCaseGenerationCandidatesResponse(val items: List<TestCaseGenerationCandidate>)

/**
 * 생성 뒤 판정을 가져오는 요청(ARTEL-1180).
 * `POST /api/projects/{projectId}/game-builds/{gameBuildId}/test-cases/verdict-carry-over`.
 *
 * @property fromGameBuildId 가져올 버전. 없으면 케이스마다 바로 앞 버전.
 * @property testCaseIds 가져올 TC. 없으면 이 버전의 모든 TC(이때는 [fromGameBuildId] 가 있어야 한다).
 * @property resetAll true 면 가져오지 않고 이 버전의 판정을 모두 다시 검증하도록 돌린다(ARTEL-1181). 나머지 칸은 보지 않는다.
 */
data class VerdictCarryOverRequest(
    val testCaseIds: List<String?>? = null,
    val fromGameBuildId: String? = null,
    val resetAll: Boolean = false,
)

/** 이어받지 못한 TC 하나와 그 까닭([VerdictCarryOverService.Skip] 의 이름). */
data class VerdictCarryOverSkipped(val testCaseId: String, val reason: String)

/** @property carried 이어받은 TC. @property skipped 이어받지 못한 TC 와 까닭. @property reset 다시 검증하도록 돌린 TC. */
data class VerdictCarryOverResponse(
    val carried: List<String>,
    val skipped: List<VerdictCarryOverSkipped>,
    val reset: List<String> = emptyList(),
)

/**
 * 판정 가져오기 미리 보기의 한 줄(ARTEL-1181). 가져올 버전에서 판정(통과 · 실패)이 난 몫 하나다.
 *
 * @property sourceStatus 가져올 버전의 판정. @property targetStatus 받는 버전의 지금 상태. 받는 버전에 없으면 null.
 * @property carryable 가져올 수 있나. 아니면 [reason] 에 까닭([VerdictCarryOverService.Skip] 의 이름).
 */
data class VerdictCarryPreviewItem(
    val testCaseId: String,
    val scene: String,
    val step: String,
    val expectedValue: String,
    val sourceStatus: String,
    val targetStatus: String?,
    val carryable: Boolean,
    val reason: String?,
)

data class VerdictCarryPreviewResponse(val items: List<VerdictCarryPreviewItem>)

/**
 * 빌드의 마지막 지도 TC 생성(ARTEL-1187). 화면 지도 SSE 의 `testCaseGeneration` 이벤트와 `snapshot` 에 실린다.
 *
 * 퍼센트를 만들지 않는다. 단계마다 분모가 달라 몫은 화면이 정한다
 * ([kr.artel.orchestration.contentmap.dto.IngestProgressResponse] 와 같은 이유).
 */
@Schema(description = "지도 TC 생성 진행")
data class TestCaseGenerationResponse(
    @Schema(description = "RUNNING · SUCCEEDED · FAILED · CANCELLED")
    val state: TestCaseGenerationState,
    @Schema(description = "READING_MAP · DRAFTING · WRITING · CARRYING_OVER. 끝난 뒤에는 마지막으로 지난 단계")
    val phase: GenerationPhase,
    @Schema(description = "지금 단계의 몇 개째. 분모가 없는 단계면 null")
    val done: Int?,
    @Schema(description = "지금 단계의 전체. DRAFTING 이면 기능 행 수, WRITING 이면 케이스 수")
    val total: Int?,
    val startedAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
    @Schema(description = "FAILED 일 때 사람에게 보일 사유")
    val error: String?,
) {
    companion object {
        fun of(status: TestCaseGenerationStatus) = TestCaseGenerationResponse(
            state = status.state,
            phase = status.phase,
            done = status.done,
            total = status.total,
            startedAt = status.startedAt,
            updatedAt = status.updatedAt,
            finishedAt = status.finishedAt,
            error = status.error,
        )
    }
}
