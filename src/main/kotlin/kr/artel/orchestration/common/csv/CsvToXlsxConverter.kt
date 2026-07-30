package kr.artel.orchestration.common.csv

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * [CsvTable]을 XLSX 바이트로 바꾼다.
 *
 * 다루는 표는 수백 행(최대 수천 행) 규모라 통짜 [XSSFWorkbook]으로 충분하다. SXSSF(스트리밍)나
 * 임시 파일은 이 규모에서 얻는 것 없이 수명 관리만 늘린다.
 *
 * POI는 블로킹 CPU 작업이지만, **이 클래스는 평범한 블로킹 함수로 둔다.** 이벤트 루프를 벗어나는
 * 일은 호출부가 한 번만 한다([TestCaseSpecService.ingest][kr.artel.orchestration.testcase.service.TestCaseSpecService.ingest]).
 * 여기서 또 `withContext`를 걸면 스레드 홉만 늘고 오프로딩 경계가 흩어진다.
 */
@Component
class CsvToXlsxConverter {

    fun convert(table: CsvTable, sheetName: String = DEFAULT_SHEET_NAME): ByteArray =
        write(table, sheetName)

    private fun write(table: CsvTable, sheetName: String): ByteArray =
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(safeSheetName(sheetName))

            val allRows = listOf(table.headers) + table.rows
            allRows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex)
                values.forEachIndexed { columnIndex, value ->
                    // 모든 칸을 문자열로 쓴다. 숫자로 추론하면 "00123"의 앞자리 0이 사라지고
                    // 긴 숫자 ID는 double로 뭉개진다. 원본 보존이 표시 형식보다 중요하다.
                    row.createCell(columnIndex).setCellValue(value)
                }
            }

            ByteArrayOutputStream().use { out ->
                workbook.write(out)
                out.toByteArray()
            }
        }

    /**
     * 엑셀 시트 이름 제약(31자, `\ / ? * [ ]` 금지)에 맞춘다. 어기면 POI가 예외를 던져,
     * 정작 중요한 데이터는 멀쩡한데 이름 하나 때문에 변환 전체가 실패한다.
     */
    private fun safeSheetName(name: String): String =
        org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(name)

    companion object {
        const val DEFAULT_SHEET_NAME: String = "TestCases"

        /** XLSX MIME 타입. S3 저장과 다운로드 응답이 같은 값을 써야 한다. */
        const val XLSX_CONTENT_TYPE: String =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
