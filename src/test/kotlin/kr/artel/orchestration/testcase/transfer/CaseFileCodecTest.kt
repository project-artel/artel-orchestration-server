package kr.artel.orchestration.testcase.transfer

import kr.artel.orchestration.common.error.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/** TC 파일 읽기 · 쓰기(ARTEL-1327). 형식은 내용으로 정하고, 머리글은 한국어 · 영어를 다 알아본다. */
class CaseFileCodecTest {

    private val row = mapOf(
        CaseFileColumn.NUMBER to "TC-012",
        CaseFileColumn.USER_NUMBER to "로그인-01",
        CaseFileColumn.KIND to "예외",
        CaseFileColumn.SCENE to "지하실",
        CaseFileColumn.PRECONDITION to "열쇠가 없다",
        CaseFileColumn.STEP to "문을, \"세게\" 연다",
        CaseFileColumn.EXPECTED to "열리지 않는다\n열쇠가 필요하다고 나온다",
        CaseFileColumn.RESULT to "미검증",
    )

    @Test
    fun `엑셀로 쓴 것을 그대로 다시 읽는다`() {
        val bytes = CaseFileCodec.write(CaseFileFormat.XLSX, listOf(row))
        assertThat(CaseFileCodec.formatOf(bytes, "아무 이름.csv")).describedAs("이름이 아니라 내용으로 정한다").isEqualTo(CaseFileFormat.XLSX)
        val read = CaseFileCodec.read(bytes, null).single()
        assertThat(read.line).isEqualTo(2)
        assertThat(read[CaseFileColumn.STEP]).isEqualTo("문을, \"세게\" 연다")
        assertThat(read[CaseFileColumn.EXPECTED]).isEqualTo("열리지 않는다\n열쇠가 필요하다고 나온다")
    }

    @Test
    fun `엑셀은 머리글 필터와 종류 고르기 목록을 단다`() {
        val bytes = CaseFileCodec.write(CaseFileFormat.XLSX, listOf(row))
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
            val sheet = book.getSheet("TC")
            assertThat(sheet.ctWorksheet.autoFilter.ref).describedAs("머리글부터 500줄까지 거른다").isEqualTo("A1:I501")
            val kind = sheet.dataValidations.single()
            assertThat(kind.validationConstraint.explicitListValues).containsExactly("기능", "예외")
            assertThat(kind.regions.cellRangeAddresses.single().formatAsString()).isEqualTo("C2:C501")
            assertThat(sheet.getRow(0).map { it.stringCellValue })
                .containsExactly("Artel 번호", "번호", "종류", "화면", "사전조건", "조작", "기대결과", "도착 화면", "결과")
        }
    }

    @Test
    fun `가져오기 양식은 빈 표와 작성법 시트다`() {
        XSSFWorkbook(ByteArrayInputStream(CaseFileCodec.template(CaseFileFormat.XLSX))).use { book ->
            assertThat((0 until book.numberOfSheets).map { book.getSheetName(it) }).containsExactly("TC", "작성법")
            val sheet = book.getSheet("TC")
            assertThat(sheet.lastRowNum).describedAs("머리글과 예시 한 줄").isEqualTo(1)
            assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo(CaseFileCodec.EXAMPLE_MARK)
            assertThat(sheet.getRow(1).getCell(5).stringCellValue).isEqualTo("문을 연다")
            assertThat(sheet.getRow(0).map { it.stringCellValue }).doesNotContain("결과").contains("Artel 번호", "종류", "화면")
            assertThat(sheet.dataValidations).hasSize(1)
            assertThat(book.getSheet("작성법").getRow(6).getCell(0).stringCellValue).isEqualTo("조작")
        }
        val csv = String(CaseFileCodec.template(CaseFileFormat.CSV), Charsets.UTF_8)
        assertThat(csv).isEqualTo("\uFEFFArtel 번호,번호,종류,화면,사전조건,조작,기대결과,도착 화면\r\n(예시),로그인-01,예외,지하실,열쇠가 없다,문을 연다,문이 열리지 않고 열쇠가 필요하다고 나온다,\r\n")
        assertThat(CaseFileCodec.read(CaseFileCodec.template(CaseFileFormat.XLSX), null)).describedAs("예시 줄은 건너뛴다").isEmpty()
        assertThat(CaseFileCodec.read(CaseFileCodec.template(CaseFileFormat.CSV), null)).isEmpty()
    }

    @Test
    fun `CSV 는 BOM 붙은 UTF-8 로 쓰고, 쉼표 · 따옴표 · 줄바꿈을 지킨다`() {
        val bytes = CaseFileCodec.write(CaseFileFormat.CSV, listOf(row))
        assertThat(bytes.take(3).map { it.toInt() and 0xFF }).containsExactly(0xEF, 0xBB, 0xBF)
        val read = CaseFileCodec.read(bytes, "tc.csv").single()
        assertThat(read[CaseFileColumn.STEP]).isEqualTo("문을, \"세게\" 연다")
        assertThat(read[CaseFileColumn.EXPECTED]).contains("\n")
        assertThat(read[CaseFileColumn.KIND]).isEqualTo("예외")
    }

    @Test
    fun `한국어 엑셀이 저장한 CP949 CSV 도 읽는다`() {
        val bytes = "번호,화면,조작,기대결과\n,광장,할머니한테 말을 건다,의뢰를 받는다\n".toByteArray(Charset.forName("MS949"))
        val read = CaseFileCodec.read(bytes, "tc.csv").single()
        assertThat(read[CaseFileColumn.SCENE]).isEqualTo("광장")
        assertThat(read[CaseFileColumn.STEP]).isEqualTo("할머니한테 말을 건다")
    }

    @Test
    fun `영어 머리글을 알아보고, 사용자 번호 칸은 우리 번호로 읽지 않는다`() {
        val csv = "No,TC 번호,id,change,kind,scene,precondition,step,expected,arrives at,result\n1,TC-001,7000,edit,exception,Cellar,No key,Open the door,It stays shut,,not saved\n"
        val read = CaseFileCodec.read(csv.toByteArray(), "list.csv").single()
        assertThat(read[CaseFileColumn.KIND]).isEqualTo("exception")
        assertThat(read[CaseFileColumn.USER_NUMBER]).describedAs("사용자 번호 칸이 여럿이면 채워진 첫 칸").isEqualTo("1")
        assertThat(read[CaseFileColumn.EXPECTED]).isEqualTo("It stays shut")
        assertThat(read.cells).describedAs("우리 번호는 Artel 번호 칸으로만 읽는다").doesNotContainKey(CaseFileColumn.NUMBER)
        assertThat(CaseFileCodec.read("Artel 번호,화면,조작,기대결과\nTC-003,광장,말을 건다,의뢰\n".toByteArray(), null).single()[CaseFileColumn.NUMBER])
            .isEqualTo("TC-003")
    }

    @Test
    fun `빈 줄은 건너뛰고 줄 번호는 파일 그대로다`() {
        val csv = "화면,조작,기대결과\n광장,말을 건다,의뢰를 받는다\n,,\n숲,덤불을 본다,고양이가 있다\n"
        assertThat(CaseFileCodec.read(csv.toByteArray(), null).map { it.line }).containsExactly(2, 4)
    }

    @Test
    fun `꼭 있어야 하는 칸이 없으면 무엇이 빠졌는지 말한다`() {
        assertThatThrownBy { CaseFileCodec.read("화면,설명\n광장,무엇\n".toByteArray(), null) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("조작 · 기대결과")
            .hasMessageContaining("화면, 설명")
    }

    @Test
    fun `옛 엑셀 xls 는 xlsx 로 저장하라고 한다`() {
        val ole2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)
        assertThatThrownBy { CaseFileCodec.read(ole2, "tc.xls") }.hasMessageContaining(".xlsx 로 저장")
    }

    @Test
    fun `500 줄을 넘으면 받지 않는다`() {
        val csv = buildString {
            append("화면,조작,기대결과\n")
            repeat(CaseFileCodec.MAX_ROWS + 1) { append("광장,말을 건다 $it,의뢰\n") }
        }
        assertThatThrownBy { CaseFileCodec.read(csv.toByteArray(), null) }.hasMessageContaining("500줄")
    }
}
