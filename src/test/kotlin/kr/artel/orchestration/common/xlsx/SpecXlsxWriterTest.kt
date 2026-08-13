package kr.artel.orchestration.common.xlsx

import kr.artel.orchestration.testcase.dto.TestCaseSpecBody
import kr.artel.orchestration.testcase.dto.TestCaseSpecEntry
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 시트의 **모양**을 못 박는다. 열 순서와 씬 병합은 눈으로 열어봐야만 보이는 값이라, 테스트가 없으면
 * 다음 변경에서 조용히 어긋난다(파일은 여전히 정상적으로 만들어지고 다운로드도 된다).
 */
class SpecXlsxWriterTest {

    private val writer = SpecXlsxWriter()

    private fun entry(scene: String, step: String, precondition: String? = null, status: String = "ready") =
        TestCaseSpecEntry(
            schemaVersion = "test-case.v1",
            spec = TestCaseSpecBody(
                scene = scene,
                precondition = precondition,
                step = step,
                expectedValue = "$step 의 기대 결과",
                status = status,
            ),
            metadata = null,
        )

    private fun sheetOf(entries: List<TestCaseSpecEntry>) =
        XSSFWorkbook(writer.write(entries).inputStream()).getSheetAt(0)

    @Test
    fun `열 순서는 씬 테스트스텝 사전조건 기대결과 상태다`() {
        val sheet = sheetOf(listOf(entry("TitleScene", "상점 입장", precondition = "로비에 있음")))

        assertThat(sheet.getRow(0).map { it.stringCellValue })
            .containsExactly("씬", "테스트 스텝", "사전조건", "기대 결과", "상태")

        // 값이 헤더와 같은 자리에 실린다 — 헤더만 맞고 값이 밀리면 표가 통째로 거짓말이 된다.
        assertThat(sheet.getRow(1).map { it.stringCellValue })
            .containsExactly("TitleScene", "상점 입장", "로비에 있음", "상점 입장 의 기대 결과", "ready")
    }

    @Test
    fun `같은 씬이 연달아 나오면 씬 칸을 하나로 합친다`() {
        val sheet = sheetOf(
            listOf(
                entry("TitleScene", "상점 입장"),
                entry("TitleScene", "검 구매"),
                entry("TitleScene", "검 장착"),
                entry("Map_scene", "스테이지 입장"),
                entry("Map_scene", "보스 입장"),
            )
        )

        // 데이터는 1행부터. TitleScene 1~3행, Map_scene 4~5행.
        assertThat(sheet.mergedRegions.map { it.formatAsString() })
            .containsExactlyInAnyOrder("A2:A4", "A5:A6")

        // 합쳐진 아래 칸은 비운다. 값이 남으면 병합을 풀거나 다른 도구로 읽을 때 씬이 여러 번 나온다.
        assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo("TitleScene")
        assertThat(sheet.getRow(2).getCell(0).stringCellValue).isEmpty()
        assertThat(sheet.getRow(3).getCell(0).stringCellValue).isEmpty()
        assertThat(sheet.getRow(4).getCell(0).stringCellValue).isEqualTo("Map_scene")
        assertThat(sheet.getRow(5).getCell(0).stringCellValue).isEmpty()

        // 씬 말고는 병합하지 않는다 — 나머지는 행마다 다른 값이다.
        assertThat(sheet.getRow(2).getCell(1).stringCellValue).isEqualTo("검 구매")
    }

    @Test
    fun `혼자인 씬은 병합하지 않는다`() {
        val sheet = sheetOf(listOf(entry("TitleScene", "상점 입장"), entry("Map_scene", "스테이지 입장")))

        assertThat(sheet.mergedRegions).isEmpty()
        assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo("TitleScene")
        assertThat(sheet.getRow(2).getCell(0).stringCellValue).isEqualTo("Map_scene")
    }

    /**
     * 정렬하지 않는다는 결정을 못 박는다. 같은 씬이 떨어져서 오면 블록도 떨어져 나오는 것이 맞다 —
     * 그게 실제로 보낸 순서이고, 우리가 다시 매기면 보낸 쪽이 의도한 흐름 순서가 사라진다.
     */
    @Test
    fun `같은 씬이 떨어져 오면 블록도 따로 만든다`() {
        val sheet = sheetOf(
            listOf(
                entry("TitleScene", "상점 입장"),
                entry("TitleScene", "검 구매"),
                entry("Map_scene", "스테이지 입장"),
                entry("TitleScene", "타이틀 복귀"),
            )
        )

        assertThat(sheet.mergedRegions.map { it.formatAsString() }).containsExactly("A2:A3")
        assertThat(sheet.getRow(3).getCell(0).stringCellValue).isEqualTo("Map_scene")
        assertThat(sheet.getRow(4).getCell(0).stringCellValue).isEqualTo("TitleScene")
    }

    @Test
    fun `케이스가 없어도 헤더만 있는 시트를 만든다`() {
        val sheet = sheetOf(emptyList())

        assertThat(sheet.getRow(0).map { it.stringCellValue })
            .containsExactly("씬", "테스트 스텝", "사전조건", "기대 결과", "상태")
        assertThat(sheet.lastRowNum).isZero()
        assertThat(sheet.mergedRegions).isEmpty()
    }
}
