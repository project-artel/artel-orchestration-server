package kr.artel.orchestration.testcase.dto

import kr.artel.orchestration.testcase.generator.MapTestCaseWriter

/**
 * build 의 content map 에서 test case 를 생성한 결과(ARTEL-971).
 *
 * 수는 모두 이 build 의 버전 몫(`test_case_version`) 기준이다(ARTEL-1018). 케이스의 정체는 버전을 넘어 하나다.
 *
 * @property folderId 이번에 새로 생긴 케이스를 넣은 folder. 새 케이스가 0건이어도 찾거나 만든 folder 를 낸다.
 * @property created 이 build 에 새로 생긴 버전 몫 수. @property updated 문구나 구조가 바뀌어 고친 몫 수.
 * @property deleted 이 build 에서 사라져 지운 몫 수(다른 버전에도 없으면 케이스째 지운 것 포함).
 * @property broken 어느 버전에도 남지 않았지만 시나리오가 인용해 지우지 않고 `BROKEN` 으로 둔 케이스 수.
 * @property regression 앞 버전에서 판정이 나 이번 몫을 `REGRESSION` 으로 시작한 수(ARTEL-994).
 */
data class ContentMapTestCaseGenerationResponse(
    val folderId: String,
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val broken: Int,
    val regression: Int,
) {
    companion object {
        fun of(result: MapTestCaseWriter.Result) = ContentMapTestCaseGenerationResponse(
            folderId = requireNotNull(result.folderId) { "생성 결과에 folder 가 없다" }.toString(),
            created = result.created,
            updated = result.updated,
            deleted = result.deleted,
            broken = result.broken,
            regression = result.regression,
        )
    }
}
