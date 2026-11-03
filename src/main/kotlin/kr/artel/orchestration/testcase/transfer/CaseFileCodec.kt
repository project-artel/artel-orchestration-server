package kr.artel.orchestration.testcase.transfer

import kr.artel.orchestration.common.error.BadRequestException
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TC 파일을 읽고 쓴다(ARTEL-1327). 형식은 파일 이름이 아니라 **내용**으로 정한다 — 엑셀(.xlsx)은 zip 이라 `PK` 로
 * 시작한다. 나머지는 CSV 로 읽는다.
 *
 * CSV 는 UTF-8 이 아니면 CP949 로 읽는다. 한국어 Windows 의 엑셀이 "CSV(쉼표로 분리)" 로 저장하면 CP949 다.
 * 내보내는 CSV 는 UTF-8 에 BOM 을 붙인다 — 붙이지 않으면 엑셀이 한글을 깨뜨린다.
 *
 * 엑셀에는 머리글 줄에 **필터**(정렬 · 거르기)를 켜고, 종류 칸에 기능 · 예외 **고르기 목록**을 건다(ARTEL-1328). CSV 는 글자뿐이라
 * 둘 다 담을 수 없다.
 */
object CaseFileCodec {
    const val MAX_ROWS = 500

    /**
     * 양식의 예시 한 줄. 작성법 시트의 예시와 같다. `Artel 번호` 는 비워 둔다 — 새 TC 는 그 칸을 비운다는 것이 그대로 보이게.
     * 이 줄과 똑같은 줄은 가져올 때 건너뛴다([isExample]). 고쳐 쓰면 TC 로 들어간다.
     */
    val EXAMPLE_ROW: Map<CaseFileColumn, String> = mapOf(
        CaseFileColumn.USER_NUMBER to "로그인-01",
        CaseFileColumn.KIND to "예외",
        CaseFileColumn.SCENE to "지하실",
        CaseFileColumn.PRECONDITION to "열쇠가 없다",
        CaseFileColumn.STEP to "문을 연다",
        CaseFileColumn.EXPECTED to "문이 열리지 않고 열쇠가 필요하다고 나온다",
    )

    /** `Artel 번호` 머리글의 메모. 칸 안에 적으면 무엇을 써야 하는 칸처럼 보여서, 마우스를 올려야 보이는 메모로 둔다. */
    const val NUMBER_NOTE = "새 TC 는 비워 두세요.\nArtel 에서 내보낸 파일이면 번호를 지우거나 고치지 마세요 — 그 번호의 TC 를 고칩니다."

    /** 종류 칸에 고를 수 있는 값. 가져올 때는 영어도 읽는다([CaseFileService.kindOf]). */
    val KIND_CHOICES = listOf("기능", "예외")

    fun formatOf(bytes: ByteArray, fileName: String?): CaseFileFormat {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) {
            return CaseFileFormat.XLSX
        }
        // 옛 엑셀(.xls, OLE2)은 읽지 않는다. 무엇을 하면 되는지 말해 준다.
        if (bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()) {
            throw BadRequestException("옛 엑셀(.xls) 파일은 읽지 못합니다. 엑셀에서 .xlsx 로 저장해 다시 올려 주세요.", "unsupported_file")
        }
        if (fileName?.lowercase()?.endsWith(".xlsx") == true) {
            throw BadRequestException("엑셀 파일을 열 수 없습니다. 파일이 손상되지 않았는지 확인해 주세요.", "unreadable_file")
        }
        return CaseFileFormat.CSV
    }

    /** 첫 시트(엑셀) · 첫 줄(CSV)을 머리글로 읽는다. 빈 줄은 건너뛴다. */
    fun read(bytes: ByteArray, fileName: String?): List<CaseFileRow> {
        if (bytes.isEmpty()) throw BadRequestException("빈 파일입니다.", "empty_file")
        val table = when (formatOf(bytes, fileName)) {
            CaseFileFormat.XLSX -> readXlsx(bytes)
            CaseFileFormat.CSV -> readCsv(bytes)
        }
        val header = table.firstOrNull() ?: throw BadRequestException("빈 파일입니다.", "empty_file")
        val columns = header.map { CaseFileColumn.of(it) }
        val missing = CaseFileColumn.REQUIRED.filter { it !in columns }
        if (missing.isNotEmpty()) {
            throw BadRequestException(
                "머리글에 ${missing.joinToString(" · ") { it.label }} 칸이 없습니다. 첫 줄의 머리글: ${header.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "(없음)" }}",
                "missing_columns",
            )
        }
        val rows = table.drop(1).mapIndexedNotNull { index, cells ->
            val values = columns.zip(cells + List((columns.size - cells.size).coerceAtLeast(0)) { "" })
                .mapNotNull { (column, value) -> column?.let { it to value } }
                // 같은 칸으로 읽히는 머리글이 둘이면(`No` · `ID`) 채워진 쪽이다.
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, found) -> found.firstOrNull { it.isNotBlank() } ?: found.first() }
            if (isExample(values) || values.values.all { it.isBlank() }) null else CaseFileRow(line = index + 2, cells = values)
        }
        if (rows.size > MAX_ROWS) throw BadRequestException("한 번에 ${MAX_ROWS}줄까지 가져올 수 있습니다. 이 파일은 ${rows.size}줄입니다.", "too_many_rows")
        return rows
    }

    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        val workbook = runCatching { XSSFWorkbook(ByteArrayInputStream(bytes)) }
            .getOrElse { throw BadRequestException("엑셀 파일을 열 수 없습니다. 파일이 손상되지 않았는지 확인해 주세요.", "unreadable_file") }
        workbook.use { book ->
            val sheet = book.getSheetAt(0) ?: return emptyList()
            val formatter = DataFormatter()
            val lines = (sheet.firstRowNum..sheet.lastRowNum).map { index ->
                val row = sheet.getRow(index) ?: return@map emptyList()
                (0 until row.lastCellNum.coerceAtLeast(0)).map { col -> row.getCell(col)?.let { formatter.formatCellValue(it) }.orEmpty() }
            }
            // 머리글은 처음으로 빈칸이 아닌 줄이다. 위에 제목 줄을 둔 시트도 있다.
            return lines.dropWhile { cells -> cells.all { it.isBlank() } }
        }
    }

    private fun readCsv(bytes: ByteArray): List<List<String>> {
        val text = decode(bytes).removePrefix("﻿")
        val format = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build()
        return CSVParser.parse(StringReader(text), format).use { parser -> parser.records.map { record -> record.toList() } }
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("MS949"))
    }

    fun write(format: CaseFileFormat, rows: List<Map<CaseFileColumn, String>>): ByteArray = when (format) {
        CaseFileFormat.XLSX -> writeXlsx(rows, guide = false)
        CaseFileFormat.CSV -> writeCsv(rows)
    }

    /**
     * 가져오기 양식. 머리글과 예시 한 줄([EXAMPLE_ROW])이다. 예시 줄은 지우지 않고 올려도 건너뛴다. 엑셀에는
     * 작성법을 둘째 시트(`작성법`)에 둔다. 결과 칸은 가져올 때 읽지 않으므로 양식에 두지 않는다.
     */
    /** 양식의 예시 줄을 그대로 둔 줄인가. 채워진 칸이 예시와 하나라도 다르면 사용자가 쓴 줄이다. */
    private fun isExample(values: Map<CaseFileColumn, String>): Boolean =
        values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() } == EXAMPLE_ROW

    fun template(format: CaseFileFormat): ByteArray = when (format) {
        CaseFileFormat.XLSX -> writeXlsx(listOf(EXAMPLE_ROW), guide = true)
        CaseFileFormat.CSV -> writeCsv(listOf(EXAMPLE_ROW), TEMPLATE_COLUMNS)
    }

    /** 양식의 칸. 내보내는 칸에서 결과(가져올 때 읽지 않음)를 뺀 것이다. */
    val TEMPLATE_COLUMNS: List<CaseFileColumn> = CaseFileColumn.EXPORTED - CaseFileColumn.RESULT

    private fun writeCsv(rows: List<Map<CaseFileColumn, String>>, columns: List<CaseFileColumn> = CaseFileColumn.EXPORTED): ByteArray {
        val out = StringWriter()
        CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").build()).use { printer ->
            printer.printRecord(columns.map { it.label })
            rows.forEach { row -> printer.printRecord(columns.map { row[it].orEmpty() }) }
        }
        return ("﻿" + out.toString()).toByteArray(Charsets.UTF_8)
    }

    private fun writeXlsx(rows: List<Map<CaseFileColumn, String>>, guide: Boolean): ByteArray {
        val columns = if (guide) TEMPLATE_COLUMNS else CaseFileColumn.EXPORTED
        XSSFWorkbook().use { book ->
            val sheet = book.createSheet("TC")
            val head = book.createCellStyle().apply {
                setFont(book.createFont().apply { bold = true })
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            val body = book.createCellStyle().apply { wrapText = true; verticalAlignment = VerticalAlignment.TOP }
            // 예시 줄은 회색 기울임꼴이다. 사람이 덮어쓰거나 지울 줄이라는 것이 보이게.
            val example = book.createCellStyle().apply {
                cloneStyleFrom(body)
                setFont(book.createFont().apply { italic = true; color = IndexedColors.GREY_50_PERCENT.index })
            }
            sheet.createRow(0).let { row ->
                columns.forEachIndexed { col, column -> row.createCell(col).apply { setCellValue(column.label); cellStyle = head } }
                columns.indexOf(CaseFileColumn.NUMBER).takeIf { it >= 0 }?.let { col ->
                    val anchor = book.creationHelper.createClientAnchor().apply { setCol1(col + 1); setCol2(col + 5); setRow1(0); setRow2(4) }
                    row.getCell(col).cellComment = sheet.createDrawingPatriarch().createCellComment(anchor).apply {
                        string = book.creationHelper.createRichTextString(NUMBER_NOTE)
                        author = "Artel"
                    }
                }
            }
            rows.forEachIndexed { index, values ->
                val row = sheet.createRow(index + 1)
                val style = if (values == EXAMPLE_ROW) example else body
                columns.forEachIndexed { col, column ->
                    row.createCell(col).apply { setCellValue(values[column].orEmpty()); cellStyle = style }
                }
            }
            sheet.createFreezePane(0, 1)
            // 머리글의 ▼ 로 칸마다 정렬 · 거르기를 한다. 빈 양식도 가져올 수 있는 만큼(500줄) 범위를 잡아 둔다.
            val lastRow = maxOf(rows.size, MAX_ROWS)
            sheet.setAutoFilter(CellRangeAddress(0, lastRow, 0, columns.lastIndex))
            kindChoices(sheet, columns.indexOf(CaseFileColumn.KIND), lastRow)
            // 글이 긴 칸(조작 · 기대결과 · 사전조건)은 넓게, 나머지는 좁게. 단위는 글자 1/256.
            columns.forEachIndexed { col, column ->
                val chars = when (column) {
                    CaseFileColumn.STEP, CaseFileColumn.EXPECTED -> 40
                    CaseFileColumn.PRECONDITION -> 24
                    CaseFileColumn.SCENE, CaseFileColumn.ARRIVES_AT -> 16
                    else -> 11
                }
                sheet.setColumnWidth(col, chars * 256)
            }
            if (guide) guideSheet(book, head, body)
            return ByteArrayOutputStream().also { book.write(it) }.toByteArray()
        }
    }

    /** 종류 칸을 기능 · 예외 중에서 고르게 한다. 다른 글을 쓰면 엑셀이 막는다. */
    private fun kindChoices(sheet: XSSFSheet, column: Int, lastRow: Int) {
        if (column < 0) return
        val helper = XSSFDataValidationHelper(sheet)
        val validation = helper.createValidation(
            helper.createExplicitListConstraint(KIND_CHOICES.toTypedArray()),
            CellRangeAddressList(1, lastRow, column, column),
        )
        validation.showErrorBox = true
        validation.createErrorBox("종류", "기능 · 예외 중 하나를 고르세요. 비우면 기능입니다.")
        sheet.addValidationData(validation)
    }

    /** 양식의 둘째 시트: 칸마다 쓰는 법과 예시 한 줄. */
    private fun guideSheet(book: XSSFWorkbook, head: org.apache.poi.xssf.usermodel.XSSFCellStyle, body: org.apache.poi.xssf.usermodel.XSSFCellStyle) {
        val sheet = book.createSheet("작성법")
        val lines = listOf(
            listOf("칸", "쓰는 법", "예시"),
            listOf(CaseFileColumn.NUMBER.label, "비워 두세요. Artel 에서 내보낸 파일에만 채워져 있고, 그 TC 를 고칠 때 씁니다. 비운 줄은 화면 · 사전조건 · 조작 · 기대결과가 같은 TC 가 있으면 그 TC 로, 없으면 새 TC 로 들어갑니다.", "TC-012"),
            listOf(CaseFileColumn.USER_NUMBER.label, "팀에서 쓰는 번호(선택). 적은 그대로 저장하고 내보낼 때 그대로 나옵니다. 겹쳐도 됩니다 — 같은 TC 를 찾는 데는 쓰지 않습니다.", "로그인-01"),
            listOf(CaseFileColumn.KIND.label, "기능 또는 예외. 예외는 조건이 맞지 않을 때 게임이 막는지 보는 TC 입니다. 비우면 기능입니다.", "예외"),
            listOf(CaseFileColumn.SCENE.label, "조작하는 화면 · 장소. 사람이 부르는 이름으로, 같은 곳은 늘 같은 이름으로 씁니다. (꼭 채움)", "지하실"),
            listOf(CaseFileColumn.PRECONDITION.label, "조작 전에 있어야 할 상황(선택).", "열쇠가 없다"),
            listOf(CaseFileColumn.STEP.label, "조작 하나. 여러 조작을 한 줄에 넣지 않습니다. (꼭 채움)", "문을 연다"),
            listOf(CaseFileColumn.EXPECTED.label, "화면에서 확인할 수 있는 결과. (꼭 채움)", "문이 열리지 않고 열쇠가 필요하다고 나온다"),
            listOf(CaseFileColumn.ARRIVES_AT.label, "하고 나면 다른 화면으로 가는 TC 면 그 화면 이름(선택).", ""),
            listOf("", "", ""),
            listOf("알아 둘 것", "빈칸은 \"그대로\" 입니다 — 파일로 칸을 지우지는 않습니다. 한 번에 ${MAX_ROWS}줄까지 가져옵니다.", ""),
            listOf("예시 줄", "TC 시트의 첫 줄(회색 기울임꼴)은 예시입니다. 그대로 두고 올려도 가져올 때 건너뜁니다. 고쳐 쓰면 TC 로 들어갑니다.", ""),
        )
        lines.forEachIndexed { index, values ->
            val row = sheet.createRow(index)
            values.forEachIndexed { col, value -> row.createCell(col).apply { setCellValue(value); cellStyle = if (index == 0) head else body } }
        }
        sheet.setColumnWidth(0, 14 * 256)
        sheet.setColumnWidth(1, 70 * 256)
        sheet.setColumnWidth(2, 36 * 256)
    }
}
