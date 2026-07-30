package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.csv.CsvReader
import kr.artel.orchestration.common.csv.CsvTable
import kr.artel.orchestration.common.csv.CsvToXlsxConverter
import kr.artel.orchestration.common.csv.MalformedCsvException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.dto.TestCaseSpecDownloadResponse
import kr.artel.orchestration.testcase.dto.TestCaseSpecIngestResult
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * Agent가 SDK 등록 시점에 보내는 **기능 테스트 명세 CSV**를 받아 두 가지를 한 흐름에서 처리한다.
 *
 * 1. **XLSX로 변환해 S3에 저장** — 사용자가 명세를 엑셀로 내려받아 공유·검토할 수 있게.
 * 2. **`test_case`로 적재** — 시나리오 저작 챗봇이 검색·연결할 케이스 라이브러리를 채운다.
 *
 * 순서가 중요하다. 저장이 먼저다: 적재는 부분 성공이 남을 수 있지만 원본 산출물(XLSX)은 어떤
 * 경우에도 남아 있어야 사용자가 무엇이 왔는지 직접 확인할 수 있다.
 *
 * **전송 방식(WS/HTTP)에 의존하지 않는다.** Agent가 어떤 경로로 CSV를 보내기로 정해지든
 * ([ARTEL-208]의 미확정 항목) 그 수신부가 [ingest]를 바이트로 부르기만 하면 된다.
 */
@Service
class TestCaseSpecService(
    private val csvReader: CsvReader,
    private val converter: CsvToXlsxConverter,
    private val storage: DocumentStorage,
    private val repository: TestCaseRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
) {

    /**
     * CSV를 변환·저장하고 케이스를 적재한다.
     *
     * 적재는 **title + category 기준 upsert**다. SDK 재등록마다 같은 명세가 다시 오는데 그때마다
     * append하면 몇 백 건이 통째로 복제된다. 이미 있는 케이스는 내용(precondition/expected)만
     * 갱신하고 `verificationStatus`·`lastVerifiedBuildId`는 **건드리지 않는다** — 그 값은 CSV가 아니라
     * QA 런이 만든 결과라, 재적재로 덮으면 검증 이력이 사라진다.
     */
    suspend fun ingest(projectId: Long, csv: ByteArray): TestCaseSpecIngestResult {
        val table = csvReader.read(csv)
        requireKnownColumns(table)

        val xlsx = converter.convert(table)
        storage.put(objectKeyFor(projectId), xlsx, CsvToXlsxConverter.XLSX_CONTENT_TYPE)
            .awaitSingleOrNull()

        return upsertCases(projectId, table)
    }

    /**
     * 명세 XLSX 다운로드 티켓. 아직 받은 명세가 없거나 비참여자면 null(→404).
     *
     * URL은 요청할 때마다 새로 만든다. 오래 사는 링크를 화면에 박아두지 않기 위해서다.
     */
    suspend fun downloadTicket(projectId: Long, userId: Long): TestCaseSpecDownloadResponse? {
        if (!projectAccessService.isMember(projectId, userId)) return null
        val objectKey = objectKeyFor(projectId)
        storage.head(objectKey).awaitSingleOrNull() ?: return null
        val presigned = storage.presignDownload(objectKey, DOWNLOAD_FILE_NAME)
        return TestCaseSpecDownloadResponse(presigned.url, presigned.expiresAt)
    }

    /**
     * 열 이름을 못 알아보면 여기서 멈춘다. 그러지 않으면 모든 행이 "필수값 없음"으로 조용히
     * 건너뛰어져, 성공 응답을 받았는데 케이스가 한 건도 안 생기는 상태가 된다.
     */
    private fun requireKnownColumns(table: CsvTable) {
        if (TITLE_HEADERS.none(table::hasHeader) || EXPECTED_HEADERS.none(table::hasHeader)) {
            throw MalformedCsvException(
                "CSV에 필요한 열이 없습니다: 제목(${TITLE_HEADERS.first()}), 기대결과(${EXPECTED_HEADERS.first()})"
            )
        }
    }

    /**
     * 행 전체를 한 트랜잭션으로 반영한다. 중간에 실패했을 때 절반만 들어간 라이브러리를 남기면,
     * 재전송이 그 절반을 갱신으로 처리해 무엇이 반영됐는지 아무도 알 수 없게 된다.
     */
    private suspend fun upsertCases(projectId: Long, table: CsvTable): TestCaseSpecIngestResult {
        var created = 0
        var updated = 0

        // 같은 CSV 안에 같은 (category, title)이 두 번 나오면 마지막 줄만 남긴다. 그대로 두면
        // 한 번의 적재에서 같은 케이스를 만들었다가 곧바로 갱신하는 셈이 된다.
        val rows = table.rows
            .mapNotNull { row -> parseRow(table, row) }
            .associateBy { it.category to it.title }
            .values
        val skipped = table.rowCount - rows.size

        transactionalOperator.executeAndAwait {
            rows.forEach { row ->
                val existing = repository.findByProjectIdAndCategoryAndTitle(
                    projectId, row.category, row.title
                )
                if (existing == null) {
                    repository.save(
                        TestCaseEntity(
                            projectId = projectId,
                            category = row.category,
                            title = row.title,
                            precondition = row.precondition,
                            expected = row.expected,
                        )
                    )
                    created++
                } else {
                    // verificationStatus/lastVerifiedBuildId는 의도적으로 그대로 둔다(QA 런의 결과).
                    repository.save(
                        existing.copy(
                            precondition = row.precondition,
                            expected = row.expected,
                        )
                    )
                    updated++
                }
            }
        }

        logger.info(
            "TestCase 명세 적재 projectId={} rows={} created={} updated={} skipped={}",
            projectId, table.rowCount, created, updated, skipped
        )
        return TestCaseSpecIngestResult(
            totalRows = table.rowCount,
            created = created,
            updated = updated,
            skipped = skipped,
        )
    }

    /** 필수값(title/expected)이 없는 행은 케이스로 만들 수 없으므로 버린다(전체 실패로 만들지 않는다). */
    private fun parseRow(table: CsvTable, row: List<String>): ParsedRow? {
        val title = TITLE_HEADERS.firstNotNullOfOrNull { table.value(row, it) } ?: return null
        val expected = EXPECTED_HEADERS.firstNotNullOfOrNull { table.value(row, it) } ?: return null
        return ParsedRow(
            category = CATEGORY_HEADERS.firstNotNullOfOrNull { table.value(row, it) }
                ?: DEFAULT_CATEGORY,
            title = title,
            precondition = PRECONDITION_HEADERS.firstNotNullOfOrNull { table.value(row, it) },
            expected = expected,
        )
    }

    private data class ParsedRow(
        val category: String,
        val title: String,
        val precondition: String?,
        val expected: String,
    )

    /**
     * 프로젝트당 한 벌만 둔다. 최신 명세가 곧 현재 상태이고, 적재도 upsert라 이전 판을 되돌아볼
     * 일이 없다. 키가 고정이라 어디에 무엇이 있는지 DB에 따로 기록할 필요도 없다.
     */
    private fun objectKeyFor(projectId: Long) = "projects/$projectId/test-case-spec/test-cases.xlsx"

    companion object {
        private val logger = LoggerFactory.getLogger(TestCaseSpecService::class.java)

        /** 사용자가 받게 될 파일 이름. 브라우저가 저장소 키 대신 이 이름으로 저장한다. */
        const val DOWNLOAD_FILE_NAME: String = "테스트케이스명세.xlsx"

        /** 분류가 비어 있어도 케이스는 만들어야 한다. 라이브러리 필터의 기본 칸이 된다. */
        const val DEFAULT_CATEGORY: String = "미분류"

        // 열 이름 후보. Agent가 어떤 표기로 내보낼지 아직 확정되지 않아 흔한 표기를 함께 받는다.
        // 이름 비교는 CsvTable이 대소문자·공백·언더스코어를 무시하고 한다.
        // TODO(ARTEL-208): ARTEL-177(CSV 생성)과 열 이름을 확정하면 후보를 하나로 줄인다.
        private val CATEGORY_HEADERS = listOf("category", "분류", "대분류")
        private val TITLE_HEADERS = listOf("title", "제목", "테스트케이스")
        private val PRECONDITION_HEADERS = listOf("precondition", "사전조건", "전제조건")
        private val EXPECTED_HEADERS = listOf("expected", "기대결과", "예상결과")
    }
}
