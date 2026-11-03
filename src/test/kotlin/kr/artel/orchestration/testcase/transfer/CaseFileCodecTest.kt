package kr.artel.orchestration.testcase.transfer

import kr.artel.orchestration.common.error.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/** TC 파일 읽기 · 쓰기(ARTEL-1327). 형식은 내용으로 정하고, 머리글은 한국어 · 영어를 다 알아본다. */
class CaseFileCodecTest {

    private val row = mapOf(
        CaseFileColumn.NUMBER to "TC-012",
        CaseFileColumn.KIND to "예외",
        CaseFileColumn.SCENE to "지하실",
        CaseFileColumn.PRECONDITION to "열쇠가 없다",
        CaseFileColumn.STEP to "문을, \"세게\" 연다",
        CaseFileColumn.EXPECTED to "열리지 않는다\n열쇠가 필요하다고 나온다",
        CaseFileColumn.NEEDS to "열쇠 == 0",
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
        assertThat(read[CaseFileColumn.NEEDS]).isEqualTo("열쇠 == 0")
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
    fun `영어 머리글과 MCP 목록 파일 머리글도 알아본다`() {
        val csv = "id,change,kind,scene,precondition,step,expected,arrives at,result\n7000,edit,exception,Cellar,No key,Open the door,It stays shut,,not saved\n"
        val read = CaseFileCodec.read(csv.toByteArray(), "list.csv").single()
        assertThat(read[CaseFileColumn.ID]).isEqualTo("7000")
        assertThat(read[CaseFileColumn.KIND]).isEqualTo("exception")
        assertThat(read[CaseFileColumn.EXPECTED]).isEqualTo("It stays shut")
        assertThat(read.cells).doesNotContainKey(CaseFileColumn.NUMBER)
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
