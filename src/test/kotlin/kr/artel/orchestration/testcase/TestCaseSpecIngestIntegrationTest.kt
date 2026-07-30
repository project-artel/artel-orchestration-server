package kr.artel.orchestration.testcase

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.csv.CsvToXlsxConverter
import kr.artel.orchestration.common.csv.MalformedCsvException
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseSpecService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

private val SPEC_CSV = """
    category,title,precondition,expected
    CONTROL,상점 입장,로비에 있음,상점 화면 진입
    RULE,검 구매,골드 10 이상,골드 차감 + 검 획득
""".trimIndent().toByteArray()

/**
 * Agent 명세 CSV 수신 통합 테스트(코루틴). 서비스 레이어로 검증한다(전송 방식이 아직 미정이라 HTTP 우회).
 *
 * 검증: XLSX 산출물이 S3(가짜 저장소)에 올라가는지, 케이스가 적재되는지, **재적재가 중복을 쌓지 않고
 * 검증 상태를 보존하는지**. 마지막 항목이 이 기능의 실제 위험 지점이다 — SDK 재등록마다 CSV가 다시 온다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseSpecIngestIntegrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: TestCaseSpecService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    @Test
    fun `CSV를 받으면 XLSX를 저장하고 케이스를 적재한다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()

        val result = service.ingest(projectId, SPEC_CSV)

        assertThat(result.totalRows).isEqualTo(2)
        assertThat(result.created).isEqualTo(2)
        assertThat(result.updated).isZero()

        val cases = testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()
        assertThat(cases.map { it.title }).containsExactlyInAnyOrder("상점 입장", "검 구매")
        assertThat(cases.map { it.verificationStatus }).containsOnly("DRAFT")
        assertThat(cases.first { it.title == "검 구매" }.precondition).isEqualTo("골드 10 이상")

        // 산출물은 프로젝트당 한 벌, 고정 키로 올라간다.
        val objectKey = "projects/$projectId/test-case-spec/test-cases.xlsx"
        val stored = fakeStorage.read(objectKey)
        assertThat(stored).isNotNull()
        assertThat(fakeStorage.contentTypeOf(objectKey))
            .isEqualTo(CsvToXlsxConverter.XLSX_CONTENT_TYPE)
        XSSFWorkbook(stored!!.inputStream()).use { workbook ->
            // 헤더 1줄 + 데이터 2줄.
            assertThat(workbook.getSheetAt(0).lastRowNum).isEqualTo(2)
        }

        val ticket = service.downloadTicket(projectId, userId)
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.downloadUrl).contains(objectKey)
    }

    @Test
    fun `같은 CSV를 다시 받아도 중복되지 않고 검증 상태를 보존한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        service.ingest(projectId, SPEC_CSV)

        // QA 런이 검증을 마친 상태를 만든다. 재적재가 이 값을 덮으면 이력이 사라진다.
        val verified = testCaseRepository.findByProjectIdAndCategoryAndTitle(projectId, "CONTROL", "상점 입장")!!
        testCaseRepository.save(verified.copy(verificationStatus = "VERIFIED", lastVerifiedBuildId = 42L))

        val updatedCsv = """
            category,title,precondition,expected
            CONTROL,상점 입장,로비에 있음,상점 화면 진입(문구 수정)
            RULE,검 구매,골드 10 이상,골드 차감 + 검 획득
        """.trimIndent().toByteArray()
        val result = service.ingest(projectId, updatedCsv)

        assertThat(result.created).isZero()
        assertThat(result.updated).isEqualTo(2)

        val cases = testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()
        assertThat(cases).hasSize(2)

        val reingested = cases.first { it.title == "상점 입장" }
        assertThat(reingested.expected).isEqualTo("상점 화면 진입(문구 수정)")
        assertThat(reingested.verificationStatus).isEqualTo("VERIFIED")
        assertThat(reingested.lastVerifiedBuildId).isEqualTo(42L)
    }

    @Test
    fun `분류가 없으면 기본 분류로 적재한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        service.ingest(projectId, "title,expected\n상점 입장,진입".toByteArray())

        val case = testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList().single()
        assertThat(case.category).isEqualTo(TestCaseSpecService.DEFAULT_CATEGORY)
    }

    @Test
    fun `필수값이 빠진 행은 건너뛰고 나머지는 적재한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        val csv = """
            category,title,precondition,expected
            CONTROL,상점 입장,로비에 있음,상점 화면 진입
            CONTROL,,없는 제목,버려질 행
        """.trimIndent().toByteArray()
        val result = service.ingest(projectId, csv)

        assertThat(result.totalRows).isEqualTo(2)
        assertThat(result.created).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `열 이름을 알아볼 수 없으면 적재하지 않고 거절한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        assertThatThrownBy {
            runBlocking { service.ingest(projectId, "foo,bar\n1,2".toByteArray()) }
        }.isInstanceOf(MalformedCsvException::class.java)

        assertThat(testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()).isEmpty()
    }

    @Test
    fun `비참여자에게는 다운로드 티켓을 주지 않는다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        service.ingest(projectId, SPEC_CSV)
        val outsiderId = newUser()

        assertThat(service.downloadTicket(projectId, outsiderId)).isNull()
    }

    @Test
    fun `아직 명세를 받지 않았으면 다운로드 티켓이 없다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()

        assertThat(service.downloadTicket(projectId, userId)).isNull()
    }

    private suspend fun newUser(): Long {
        val now = Instant.now()
        return appUserRepository.save(AppUserEntity(displayName = "spec-user", createdAt = now, updatedAt = now)).id!!
    }

    /** 새 프로젝트 + 그 프로젝트에 참여하는 사용자 하나(실 행, 자동 id라 테스트끼리 격리된다). */
    private suspend fun newProjectWithMember(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = newUser()
        val projectId = projectRepository.save(
            ProjectEntity(name = "spec-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        return projectId to userId
    }
}
