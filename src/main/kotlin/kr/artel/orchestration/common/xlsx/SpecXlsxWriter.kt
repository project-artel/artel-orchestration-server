package kr.artel.orchestration.common.xlsx

import kr.artel.orchestration.testcase.dto.TestCaseSpecEntry
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.jetbrains.kotlinx.dataframe.io.writeExcel
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * 명세 케이스 목록을 사용자가 받아 볼 XLSX 한 장으로 만든다(ARTEL-329).
 *
 * 명세가 CSV로 오던 시절에는 받은 표를 그대로 시트에 옮기면 됐다. JSON으로 바뀌면서 표라는 것이
 * 사라졌으므로 여기서 **읽을 열을 정한다** — 그래서 이 클래스는 변환기가 아니라 *뷰*다. 사람이 명세를
 * 검토할 때 필요한 것만 담고, `metadata`(출처·생성 근거)는 담지 않는다: 사람이 읽을 값이 아니고
 * 한 칸에 JSON을 통째로 넣으면 시트가 못 쓰게 된다.
 *
 * 표를 손으로 조립하지 않고 Kotlin DataFrame에 맡긴다. 셀 타입·헤더·너비를 직접 다루던 코드가
 * 사라지고, 열을 더하고 싶으면 [COLUMNS]에 한 줄을 더하면 된다.
 *
 * **블로킹·CPU 작업이다.** 호출부가 `Dispatchers.IO`로 감싸야 한다(이벤트 루프에서 돌면 그 스레드에
 * 걸린 무관한 요청이 함께 멈춘다).
 */
@Component
class SpecXlsxWriter {

    /**
     * 빈 목록도 시트를 만든다. 헤더만 있는 파일이 "아직 케이스가 없다"를 말해 주는 반면, 파일이
     * 없으면 다운로드가 404가 되어 "명세를 보낸 적이 없다"와 구분되지 않는다.
     */
    fun write(entries: List<TestCaseSpecEntry>): ByteArray {
        val frame = dataFrameOf(*COLUMNS.keys.toTypedArray())(
            *entries.flatMap { entry -> COLUMNS.values.map { read -> read(entry) } }.toTypedArray()
        )
        // writeExcel은 File/경로/Workbook만 받는다. 임시 파일을 만들지 않으려고 워크북을 직접 쥐고
        // 바이트로 뽑는다 — 시트를 채우는 일 자체는 여전히 DataFrame이 한다.
        return XSSFWorkbook().use { workbook ->
            frame.writeExcel(workbook, sheetName = SHEET_NAME)
            mergeSceneRuns(workbook, entries.map { it.spec.scene.orEmpty() })
            ByteArrayOutputStream().use { out ->
                workbook.write(out)
                out.toByteArray()
            }
        }
    }

    /**
     * 같은 씬이 연달아 나오는 구간의 씬 칸을 하나로 합친다.
     *
     * **정렬하지 않는다.** 보낸 순서가 곧 시트의 순서다 — 명세가 이미 씬별로 뭉쳐 오고(실측: 66건이
     * 6개 구간 = 6개 씬), 순서를 우리가 다시 매기면 보낸 쪽이 의도한 흐름 순서가 사라진다. 뭉치지
     * 않은 채로 오면 같은 씬이 여러 블록으로 나뉘어 보이는데, 그게 실제 모습이므로 그대로 두는 것이 맞다.
     *
     * 합쳐지는 아래 칸의 값은 지운다. 병합은 보이는 것만 가리므로 값이 남아 있으면 병합을 풀거나
     * 다른 도구로 읽을 때 같은 씬이 여러 번 나온다.
     */
    private fun mergeSceneRuns(workbook: XSSFWorkbook, scenes: List<String>) {
        if (scenes.size < 2) return
        val sheet = workbook.getSheet(SHEET_NAME) ?: return
        val sceneStyle = workbook.createCellStyle().apply {
            verticalAlignment = VerticalAlignment.CENTER
        }

        var start = 0
        while (start < scenes.size) {
            var end = start
            while (end + 1 < scenes.size && scenes[end + 1] == scenes[start]) end++

            // 헤더가 0행이라 데이터는 1행부터다.
            val firstRow = start + HEADER_ROWS
            val lastRow = end + HEADER_ROWS
            sheet.getRow(firstRow)?.getCell(SCENE_COLUMN)?.cellStyle = sceneStyle
            if (lastRow > firstRow) {
                for (rowIndex in (firstRow + 1)..lastRow) {
                    sheet.getRow(rowIndex)?.getCell(SCENE_COLUMN)?.setBlank()
                }
                sheet.addMergedRegion(CellRangeAddress(firstRow, lastRow, SCENE_COLUMN, SCENE_COLUMN))
            }
            start = end + 1
        }
    }

    companion object {
        const val XLSX_CONTENT_TYPE: String =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        private const val SHEET_NAME = "테스트케이스"

        /** 헤더가 차지하는 행 수. 데이터는 이 다음 행부터 시작한다. */
        private const val HEADER_ROWS = 1

        /** 병합 대상인 씬 열의 위치. [COLUMNS]의 첫 열이다. */
        private const val SCENE_COLUMN = 0

        /**
         * 시트의 열. 순서가 곧 열 순서다.
         *
         * 씬 다음이 **테스트 스텝**인 것은 읽는 사람이 먼저 찾는 것이 "무엇을 하는가"이기 때문이다.
         * 사전조건은 그 스텝을 돌리기 위한 조건이라 스텝을 읽은 뒤에 필요해지고, 실제 명세에서 가장
         * 긴 칸이기도 해서 앞에 두면 스텝과 기대 결과가 화면 밖으로 밀린다.
         *
         * 한국어 헤더인 것은 이 파일을 여는 사람이 QA 담당자이기 때문이다. `spec`의 영문 키를 그대로
         * 쓰면 저장 스키마와는 맞지만 읽는 사람과는 안 맞는다.
         */
        private val COLUMNS: Map<String, (TestCaseSpecEntry) -> String> = linkedMapOf(
            "씬" to { it.spec.scene.orEmpty() },
            "테스트 스텝" to { it.spec.step.orEmpty() },
            "사전조건" to { it.spec.precondition.orEmpty() },
            "기대 결과" to { it.spec.expectedValue.orEmpty() },
            "상태" to { it.spec.status.orEmpty() },
        )
    }
}
