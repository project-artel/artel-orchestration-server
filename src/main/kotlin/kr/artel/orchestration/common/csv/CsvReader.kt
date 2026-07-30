package kr.artel.orchestration.common.csv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.artel.orchestration.common.error.BadRequestException
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * CSV 바이트를 [CsvTable]로 읽는다.
 *
 * 파싱은 순수 CPU 작업이고 표가 수천 행을 넘지 않으므로 통째로 메모리에 올린다. 그래도 이벤트
 * 루프(reactor-http-nio)에서 돌리면 그동안 다른 요청이 밀리므로 [Dispatchers.IO]로 옮긴다.
 *
 * 인코딩은 UTF-8로 고정한다. Agent가 만드는 파일이므로 협상할 상대가 없고, 잘못 짚어 깨진 글자를
 * 그대로 DB에 넣는 것보다 낫다. Excel이 붙이는 BOM은 첫 헤더 이름에 섞이지 않도록 걷어낸다.
 */
@Component
class CsvReader {

    suspend fun read(content: ByteArray, maxRows: Int = MAX_ROWS): CsvTable =
        withContext(Dispatchers.IO) {
            require(maxRows > 0) { "maxRows must be positive" }
            parse(content, maxRows)
        }

    private fun parse(content: ByteArray, maxRows: Int): CsvTable {
        val records = try {
            InputStreamReader(content.inputStream(), StandardCharsets.UTF_8).use { reader ->
                CSVParser(reader, FORMAT).use { it.records.toList() }
            }
        } catch (error: IOException) {
            // 파싱 실패는 서버 잘못이 아니라 받은 파일이 CSV가 아니라는 뜻이다(→ 400).
            // 4xx는 advice가 원인을 찍지 않으므로 진단용 원문은 여기서 남긴다.
            logger.warn("CSV 파싱 실패", error)
            throw MalformedCsvException()
        }

        if (records.isEmpty()) throw MalformedCsvException("CSV에 헤더 줄이 없습니다.")

        val headers = records.first().toList().mapIndexed { index, value ->
            if (index == 0) value.removePrefix(BOM) else value
        }.map { it.trim() }

        val rows = records.drop(1)
            // 엑셀에서 저장한 파일 끝에 흔히 남는 빈 줄. 이걸 케이스로 적재하면 빈 행이 생긴다.
            .filterNot { record -> record.all { it.isBlank() } }
            .map { record ->
                // 짧은 줄을 그대로 두면 소비처마다 인덱스 경계를 다시 확인해야 한다. 여기서 한 번 맞춘다.
                List(headers.size) { index -> record.toList().getOrNull(index).orEmpty() }
            }

        if (rows.size > maxRows) {
            throw CsvTooLargeException(rows.size, maxRows)
        }
        return CsvTable(headers = headers, rows = rows)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CsvReader::class.java)

        /**
         * 한 번에 받아들일 최대 데이터 행 수(헤더 제외).
         *
         * 예상 규모는 약 300행이고 최대치도 수천 행 수준이라, 이 상한은 성능 한계가 아니라
         * "명백히 잘못된 파일"을 막는 안전장치다. 전량을 메모리에 올리므로 무제한일 수는 없다.
         *
         * TODO(ARTEL-208): 실제 SDK 등록 데이터 규모를 보고 팀에서 확정한다.
         */
        const val MAX_ROWS: Int = 5_000

        private const val BOM = "﻿"

        private val FORMAT: CSVFormat = CSVFormat.DEFAULT.builder()
            // 헤더를 CSVFormat에 맡기지 않고 첫 레코드로 직접 읽는다. 중복·빈 헤더 이름이 있을 때
            // commons-csv가 예외를 던지는 대신, 우리 쪽에서 열 이름을 정규화해 다루기 위해서다.
            .setIgnoreSurroundingSpaces(true)
            .setIgnoreEmptyLines(true)
            .build()
    }
}

/**
 * 받은 바이트가 CSV로 읽히지 않는다. 보낸 쪽 문제이므로 400.
 *
 * 원인 예외는 싣지 않는다. [ApiException][kr.artel.orchestration.common.error.ApiException]은
 * cause를 생성자에서 확정하므로 나중에 `initCause`로 끼울 수 없고, 4xx는 advice가 원인을 찍지도
 * 않는다. 진단이 필요한 원문은 던지는 쪽에서 로그로 남긴다.
 */
class MalformedCsvException(message: String = "CSV 형식이 올바르지 않습니다.") :
    BadRequestException(message, code = "malformed_csv")

/**
 * 행 수가 상한을 넘었다.
 *
 * 413(Payload Too Large)이 더 정확해 보이지만 [BadRequestException] 계열로 두어 400으로 응답한다.
 * 상한은 바이트 크기가 아니라 행 수이고, 이 계층에 413 전용 예외를 새로 만들 만큼 쓰임이 넓지 않다.
 * 몇 행이 왔고 몇 행까지 되는지는 도메인 정보이므로 메시지에 실어도 서버 내부가 새지 않는다.
 */
class CsvTooLargeException(actualRows: Int, maxRows: Int) : BadRequestException(
    "CSV는 한 번에 최대 ${maxRows}행까지 처리할 수 있습니다(받은 행: $actualRows).",
    code = "csv_too_large"
)
