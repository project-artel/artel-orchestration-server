package kr.artel.orchestration.common.csv

import kr.artel.orchestration.common.error.ApiException
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * CSV 읽기와 XLSX 변환 단위 테스트. Spring 컨텍스트도 DB도 코루틴도 필요 없다 —
 * 두 컴포넌트는 평범한 블로킹 함수이고, 이벤트 루프를 벗어나는 일은 서비스가 한 곳에서 한다.
 *
 * 핵심 검증은 **원본 보존**이다. 변환한 통합문서를 다시 읽어 값이 그대로인지 보고, 특히 숫자로
 * 보이는 값이 숫자로 바뀌지 않았는지 확인한다(앞자리 0·긴 ID가 깨지는 실제 사고 지점).
 */
class CsvToXlsxConverterTest {

    private val reader = CsvReader()
    private val converter = CsvToXlsxConverter()

    @Test
    fun `변환한 XLSX를 다시 읽으면 CSV 값이 그대로다`() {
        val csv = """
            category,title,precondition,expected
            CONTROL,상점 입장,로비에 있음,상점 화면 진입
            RULE,검 구매,골드 10 이상,골드 차감 + 검 획득
        """.trimIndent().toByteArray()

        val sheet = convertAndRead(csv)

        assertThat(sheet).containsExactly(
            listOf("category", "title", "precondition", "expected"),
            listOf("CONTROL", "상점 입장", "로비에 있음", "상점 화면 진입"),
            listOf("RULE", "검 구매", "골드 10 이상", "골드 차감 + 검 획득"),
        )
    }

    @Test
    fun `숫자로 보이는 값도 문자열로 남아 앞자리 0이 살아있다`() {
        val csv = """
            code,buildId,title
            00123,9007199254740993,앞자리 0 보존
        """.trimIndent().toByteArray()

        val bytes = converter.convert(reader.read(csv))

        XSSFWorkbook(bytes.inputStream()).use { workbook ->
            val row = workbook.getSheetAt(0).getRow(1)
            // 숫자 타입으로 저장되면 "00123"은 123이 되고 큰 정수는 double로 뭉개진다.
            assertThat(row.getCell(0).cellType).isEqualTo(CellType.STRING)
            assertThat(row.getCell(0).stringCellValue).isEqualTo("00123")
            assertThat(row.getCell(1).cellType).isEqualTo(CellType.STRING)
            // 지수 표기(9.007199254740992E15)로 새지 않는다.
            assertThat(row.getCell(1).stringCellValue).isEqualTo("9007199254740993")
        }
    }

    @Test
    fun `따옴표로 감싼 콤마와 개행이 한 칸으로 유지된다`() {
        val csv = "title,expected\n\"검, 방패 구매\",\"첫 줄\n둘째 줄\"\n".toByteArray()

        val sheet = convertAndRead(csv)

        assertThat(sheet[1]).containsExactly("검, 방패 구매", "첫 줄\n둘째 줄")
    }

    @Test
    fun `헤더만 있는 CSV는 헤더 한 줄짜리 시트가 된다`() {
        val table = reader.read("category,title,expected".toByteArray())

        assertThat(table.rowCount).isZero()
        assertThat(convertAndRead("category,title,expected".toByteArray()))
            .containsExactly(listOf("category", "title", "expected"))
    }

    @Test
    fun `빈 CSV는 400으로 거절한다`() {
        assertThatThrownBy { reader.read(ByteArray(0)) }
            .isInstanceOf(MalformedCsvException::class.java)
            .satisfies({ assertThat((it as ApiException).status).isEqualTo(HttpStatus.BAD_REQUEST) })
    }

    @Test
    fun `행 수 상한을 넘으면 거절한다`() {
        val csv = buildString {
            appendLine("title,expected")
            repeat(6) { appendLine("케이스 $it,기대 $it") }
        }.toByteArray()

        assertThatThrownBy { reader.read(csv, maxRows = 5) }
            .isInstanceOf(CsvTooLargeException::class.java)
            // 몇 행이 왔고 몇 행까지 되는지는 도메인 안내이므로 클라이언트에 나가도 된다.
            .hasMessageContaining("6")
            .hasMessageContaining("5")
    }

    /** 기본 상한은 예상 규모(약 300행)의 10배 이상이어야 정상 파일을 막지 않는다. */
    @Test
    fun `기본 행 상한은 예상 규모보다 넉넉하다`() {
        assertThat(CsvReader.MAX_ROWS).isBetween(3_000, 9_000)
    }

    @Test
    fun `BOM이 붙어도 첫 헤더 이름을 알아본다`() {
        val csv = ("﻿" + "title,expected\n상점 입장,진입").toByteArray()

        val table = reader.read(csv)

        assertThat(table.hasHeader("title")).isTrue()
        assertThat(table.value(table.rows.first(), "title")).isEqualTo("상점 입장")
    }

    @Test
    fun `헤더 표기가 달라도 같은 열로 본다`() {
        val table = reader.read("Test_Case,Expected Result\n상점 입장,진입".toByteArray())

        assertThat(table.hasHeader("testcase")).isTrue()
        assertThat(table.value(table.rows.first(), "expectedresult")).isEqualTo("진입")
    }

    @Test
    fun `끝에 남은 빈 줄은 행으로 세지 않는다`() {
        val table = reader.read("title,expected\n상점 입장,진입\n,\n".toByteArray())

        assertThat(table.rowCount).isEqualTo(1)
    }

    /** 변환 결과를 다시 열어 시트를 문자열 표로 되돌린다. */
    private fun convertAndRead(csv: ByteArray): List<List<String>> {
        val bytes = converter.convert(reader.read(csv))
        return XSSFWorkbook(bytes.inputStream()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            (0..sheet.lastRowNum).map { rowIndex ->
                val row = sheet.getRow(rowIndex)
                (0 until row.lastCellNum).map { row.getCell(it)?.stringCellValue.orEmpty() }
            }
        }
    }
}
