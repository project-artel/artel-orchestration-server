package kr.artel.orchestration.testcase.dto

import kr.artel.orchestration.testcase.generator.MapTestCaseWriter

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
 */
data class ContentMapTestCaseGenerationResponse(
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val broken: Int,
    val regression: Int,
    val carriedOver: Int,
) {
    companion object {
        fun of(result: MapTestCaseWriter.Result, carriedOver: Int) = ContentMapTestCaseGenerationResponse(
            created = result.created,
            updated = result.updated,
            deleted = result.deleted,
            broken = result.broken,
            regression = result.regression,
            carriedOver = carriedOver,
        )
    }
}

/**
 * 라이브러리의 "지도에서 TC 생성" 모달에 보일 게임 빌드 하나(ARTEL-1040). editor build 는 없다.
 *
 * @property mapState 지도가 어디까지 왔나. `READY` 일 때만 만들 수 있다.
 * @property generated 이미 이 빌드에서 지도로 TC 를 만들었다. 같은 빌드에 다시 만들 수 없다.
 */
data class TestCaseGenerationCandidate(
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val createdAt: java.time.Instant,
    val mapState: MapState,
    val generated: Boolean,
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
