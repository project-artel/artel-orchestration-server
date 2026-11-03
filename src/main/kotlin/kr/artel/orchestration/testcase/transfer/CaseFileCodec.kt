package kr.artel.orchestration.testcase.transfer

import kr.artel.orchestration.common.error.BadRequestException
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
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
 */
object CaseFileCodec {
    const val MAX_ROWS = 500

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
                .toMap()
            if (values.values.all { it.isBlank() }) null else CaseFileRow(line = index + 2, cells = values)
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
        CaseFileFormat.XLSX -> writeXlsx(rows)
        CaseFileFormat.CSV -> writeCsv(rows)
    }

    private fun writeCsv(rows: List<Map<CaseFileColumn, String>>): ByteArray {
        val out = StringWriter()
        CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").build()).use { printer ->
            printer.printRecord(CaseFileColumn.EXPORTED.map { it.label })
            rows.forEach { row -> printer.printRecord(CaseFileColumn.EXPORTED.map { row[it].orEmpty() }) }
        }
        return ("﻿" + out.toString()).toByteArray(Charsets.UTF_8)
    }

    private fun writeXlsx(rows: List<Map<CaseFileColumn, String>>): ByteArray {
        XSSFWorkbook().use { book ->
            val sheet = book.createSheet("TC")
            val head = book.createCellStyle().apply {
                setFont(book.createFont().apply { bold = true })
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            val body = book.createCellStyle().apply { wrapText = true; verticalAlignment = VerticalAlignment.TOP }
            sheet.createRow(0).let { row ->
                CaseFileColumn.EXPORTED.forEachIndexed { col, column -> row.createCell(col).apply { setCellValue(column.label); cellStyle = head } }
            }
            rows.forEachIndexed { index, values ->
                val row = sheet.createRow(index + 1)
                CaseFileColumn.EXPORTED.forEachIndexed { col, column ->
                    row.createCell(col).apply { setCellValue(values[column].orEmpty()); cellStyle = body }
                }
            }
            sheet.createFreezePane(0, 1)
            // 글이 긴 칸(조작 · 기대결과 · 사전조건)은 넓게, 나머지는 좁게. 단위는 글자 1/256.
            CaseFileColumn.EXPORTED.forEachIndexed { col, column ->
                val chars = when (column) {
                    CaseFileColumn.STEP, CaseFileColumn.EXPECTED -> 40
                    CaseFileColumn.PRECONDITION, CaseFileColumn.NEEDS, CaseFileColumn.LEAVES -> 24
                    CaseFileColumn.SCENE, CaseFileColumn.ARRIVES_AT -> 16
                    else -> 10
                }
                sheet.setColumnWidth(col, chars * 256)
            }
            return ByteArrayOutputStream().also { book.write(it) }.toByteArray()
        }
    }
}
