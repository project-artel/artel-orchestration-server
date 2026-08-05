package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kr.artel.orchestration.common.csv.CsvReader
import kr.artel.orchestration.common.csv.CsvTable
import kr.artel.orchestration.common.csv.CsvToXlsxConverter
import kr.artel.orchestration.common.csv.MalformedCsvException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.dto.TestCaseSpecDownloadResponse
import kr.artel.orchestration.testcase.dto.TestCaseSpecIngestResult
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseEmbeddingRepository
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
 * 순서가 중요하다. **S3 저장이 마지막이다**: 앞 단계가 실패했는데 버킷에만 파일이 남는 고아 상태와,
 * DB와 산출물이 어긋난 채 다운로드되는 상태를 막는다. 자세한 근거는 [ingest] 참조.
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
    private val projectRepository: ProjectRepository,
    private val transactionalOperator: TransactionalOperator,
    private val embeddingRepository: TestCaseEmbeddingRepository,
) {

    /**
     * CSV를 적재하고 XLSX 산출물을 저장한다.
     *
     * **오프로딩 경계는 여기 한 곳뿐이다.** CSV 파싱과 POI 변환은 블로킹 CPU 작업이라 이벤트
     * 루프(reactor-http-nio)에서 돌면 그 스레드에 걸린 무관한 요청이 전부 멈춘다. 아래 컴포넌트들은
     * 평범한 블로킹 함수로 두고 이 `withContext` 하나로 감싼다 — 각자 감싸면 한 요청에 스레드 홉이
     * 네 번까지 늘고, 어디서 이벤트 루프를 벗어나는지 코드에서 보이지 않게 된다.
     *
     * DB(R2DBC)와 S3(비동기 클라이언트)는 블로킹이 아니라 이 블록 안에서도 스레드를 붙잡지 않고
     * 코루틴이 그냥 대기한다. 굳이 밖으로 뺄 이유가 없어 경계를 쪼개지 않는다.
     *
     * **순서가 계약이다: 검증 → 변환 → DB 커밋 → S3 저장.**
     * S3를 마지막에 두어야 앞 단계가 실패했을 때 고아 객체가 남지 않는다. 열을 못 알아봐 400을
     * 냈는데 버킷에는 파일이 올라가 있거나, 트랜잭션이 롤백됐는데 다운로드는 되는 상태를 막는다.
     * 남는 실패 모드는 "DB는 최신인데 XLSX만 이전 판"뿐이고, 키가 프로젝트별 고정값이라 재전송이
     * 덮어쓰기(멱등)로 복구한다.
     *
     * 적재는 **title + category 기준 upsert**다. SDK 재등록마다 같은 명세가 다시 오는데 그때마다
     * append하면 몇 백 건이 통째로 복제된다. 이미 있는 케이스는 내용(precondition/expected)만
     * 갱신하고 `verificationStatus`·`lastVerifiedBuildId`는 **건드리지 않는다** — 그 값은 CSV가 아니라
     * QA 런이 만든 결과라, 재적재로 덮으면 검증 이력이 사라진다.
     */
    suspend fun ingest(projectId: Long, csv: ByteArray): TestCaseSpecIngestResult =
        withContext(Dispatchers.IO) {
            // 없는(또는 삭제된) 프로젝트면 여기서 멈춘다. 이 경로에는 엔드유저가 없어 멤버십으로
            // 걸러낼 수 없고, 확인하지 않으면 오타 난 projectId로 보낸 케이스가 아무도 볼 수 없는
            // 곳에 조용히 쌓인다.
            projectRepository.findActiveById(projectId) ?: throw NotFoundException()

            val table = csvReader.read(csv)
            requireKnownColumns(table)
            val xlsx = converter.convert(table)

            val result = upsertCases(projectId, table)
            storage.put(objectKeyFor(projectId), xlsx, CsvToXlsxConverter.XLSX_CONTENT_TYPE)
                .awaitSingleOrNull()
            result
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
                    // 신규 케이스의 임베딩은 여기서 만들지 않는다 — 백필 워커의 주기 seedPending이
                    // 아직 벡터 없는 케이스를 자동으로 픽업한다(적재를 LLM 호출로 늦추지 않는다).
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
                    // 임베딩 텍스트(category/title/precondition/expected) 중 매칭 키(category/title)는
                    // 불변이라, 내용 변화는 precondition/expected로만 온다. 바뀌었을 때만 옛 벡터를 버려
                    // 다음 백필 tick이 새 본문으로 재임베딩하게 한다(안 바뀌면 불필요한 재임베딩을 피한다).
                    val contentChanged =
                        existing.precondition != row.precondition || existing.expected != row.expected
                    // verificationStatus/lastVerifiedBuildId는 의도적으로 그대로 둔다(QA 런의 결과).
                    repository.save(
                        existing.copy(
                            precondition = row.precondition,
                            expected = row.expected,
                        )
                    )
                    if (contentChanged) embeddingRepository.discardFor(existing.id!!)
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
