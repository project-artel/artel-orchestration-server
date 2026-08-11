package kr.artel.orchestration.common.xlsx

import kr.artel.orchestration.testcase.dto.TestCaseSpecEntry
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
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
            ByteArrayOutputStream().use { out ->
                workbook.write(out)
                out.toByteArray()
            }
        }
    }

    companion object {
        const val XLSX_CONTENT_TYPE: String =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        private const val SHEET_NAME = "테스트케이스"

        /**
         * 시트의 열. 순서가 곧 열 순서다.
         *
         * 한국어 헤더인 것은 이 파일을 여는 사람이 QA 담당자이기 때문이다. `spec`의 영문 키를 그대로
         * 쓰면 저장 스키마와는 맞지만 읽는 사람과는 안 맞는다.
         */
        private val COLUMNS: Map<String, (TestCaseSpecEntry) -> String> = linkedMapOf(
            "씬" to { it.spec.scene.orEmpty() },
            "사전조건" to { it.spec.precondition.orEmpty() },
            "테스트 스텝" to { it.spec.step.orEmpty() },
            "기대 결과" to { it.spec.expectedValue.orEmpty() },
            "상태" to { it.spec.status.orEmpty() },
        )
    }
}
