package kr.artel.orchestration.testcase.dto

import kr.artel.orchestration.testcase.generator.MapTestCaseWriter

/**
 * build 의 content map 에서 test case 를 생성한 결과(ARTEL-971).
 *
 * @property folderId 새 test case 를 넣은 folder. 새 행이 0건이어도 찾거나 만든 folder 를 낸다.
 * @property created 새로 만든 행 수. @property updated 문구나 구조가 바뀌어 고친 행 수.
 * @property deleted content map 에서 사라져 지운 행 수. @property broken 사라졌지만 시나리오가 인용해
 *   지우지 않고 `BROKEN` 으로 바꾼 행 수.
 */
data class ContentMapTestCaseGenerationResponse(
    val folderId: String,
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val broken: Int,
) {
    companion object {
        fun of(result: MapTestCaseWriter.Result) = ContentMapTestCaseGenerationResponse(
            folderId = requireNotNull(result.folderId) { "생성 결과에 folder 가 없다" }.toString(),
            created = result.created,
            updated = result.updated,
            deleted = result.deleted,
            broken = result.broken,
        )
    }
}
