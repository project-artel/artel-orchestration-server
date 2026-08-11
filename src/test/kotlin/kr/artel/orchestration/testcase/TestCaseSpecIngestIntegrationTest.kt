package kr.artel.orchestration.testcase

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.xlsx.SpecXlsxWriter
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.dto.TestCaseSpecEntry
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseSpecService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 명세 한 벌을 만든다. `revision`을 바꿔 가며 같은 판/새 판을 흉내낸다.
 *
 * `spec_id`를 케이스마다 다르게 주는 것이 요점이다 — 멱등이 그 값으로 걸리므로, 문구를 바꾼
 * 재전송이 같은 행으로 이어지는지를 이걸로 확인한다.
 */
private fun spec(
    revision: Int = 1,
    cases: String = DEFAULT_CASES,
): ByteArray = """
    {
      "id": "spec-doc-1",
      "revision": $revision,
      "cases": [$cases],
      "created_at": "2026-08-11T00:00:00Z",
      "updated_at": "2026-08-11T00:00:00Z"
    }
""".trimIndent().toByteArray()

private fun case(
    specId: String,
    scene: String = "TitleScene",
    step: String,
    precondition: String? = "TitleScene 화면인 상태",
    expectedValue: String,
    status: String = "ready",
): String = """
    {
      "schema_version": "test-case.v1",
      "spec": {
        "scene": "$scene",
        "precondition": ${precondition?.let { "\"$it\"" } ?: "null"},
        "step": "$step",
        "expected_value": "$expectedValue",
        "status": "$status"
      },
      "metadata": {
        "source": { "spec_id": "$specId", "scene_key": "$scene",
                    "used_step_indexes": [0], "evidence_gaps": [] },
        "generation": { "build_evidence": "3acc85dd94fe227e", "capture": "editor",
                        "prompt_version": null, "llm_model": null }
      }
    }
""".trimIndent()

private val DEFAULT_CASES = listOf(
    case(specId = "scenario:aaa:1", step = "상점 입장", expectedValue = "상점 화면 진입"),
    case(specId = "scenario:aaa:2", step = "검 구매", expectedValue = "골드 차감 + 검 획득"),
).joinToString(",")

/**
 * Agent 명세 JSON 수신 통합 테스트(코루틴). 서비스 레이어로 검증한다(전송 방식이 아직 미정이라 HTTP 우회).
 *
 * 검증 축은 넷이고, 넷 다 깨져도 응답은 성공으로 보인다:
 * 1. XLSX 산출물이 저장소에 올라가고 케이스가 적재되는가
 * 2. **재적재가 중복을 쌓지 않고 QA 검증 상태를 보존하는가** — SDK 재등록마다 명세가 다시 온다
 * 3. **같은 revision이면 아예 손대지 않는가**
 * 4. 앞 단계가 실패했을 때 저장소에 고아 객체가 남지 않는가
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseSpecIngestIntegrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()

        @Bean
        @Primary
        fun recordingWriter(): SpecXlsxWriter = RecordingWriter()
    }

    /** XLSX 생성이 실제로 어느 스레드에서 돌았는지 기록한다. 추측 대신 실측하려고 끼운다. */
    class RecordingWriter : SpecXlsxWriter() {
        @Volatile var executedOn: String? = null

        override fun write(entries: List<TestCaseSpecEntry>): ByteArray {
            executedOn = Thread.currentThread().name
            return super.write(entries)
        }
    }

    @Autowired private lateinit var service: TestCaseSpecService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var xlsxWriter: SpecXlsxWriter
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    @Test
    fun `명세를 받으면 XLSX를 저장하고 케이스를 적재한다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()

        val result = service.ingest(projectId, spec())

        assertThat(result.totalCases).isEqualTo(2)
        assertThat(result.created).isEqualTo(2)
        assertThat(result.updated).isZero()
        assertThat(result.unchanged).isFalse()

        val cases = testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()
        assertThat(cases.map { it.step }).containsExactlyInAnyOrder("상점 입장", "검 구매")
        assertThat(cases.map { it.scene }).containsOnly("TitleScene")
        // 명세 쪽 상태와 우리 QA 런의 상태는 다른 축이고, 적재는 앞의 것만 채운다.
        assertThat(cases.map { it.status }).containsOnly("ready")
        assertThat(cases.map { it.verificationStatus }).containsOnly("DRAFT")
        assertThat(cases.first { it.step == "검 구매" }.expectedValue).isEqualTo("골드 차감 + 검 획득")

        // 봉투에서 취한 값들이 행마다 찍힌다.
        assertThat(cases.map { it.specRevision }).containsOnly(1)
        assertThat(cases.map { it.schemaVersion }).containsOnly("test-case.v1")
        assertThat(cases.map { it.sourceSentAt }).containsOnly(Instant.parse("2026-08-11T00:00:00Z"))

        // metadata는 해석하지 않고 통째로 남는다 — 나중에 "이 케이스가 어디서 왔나"에 답하는 유일한 값이다.
        val metadata = cases.first().metadata.asString()
        assertThat(metadata).contains("build_evidence").contains("scene_key")

        val objectKey = objectKeyOf(projectId)
        val stored = fakeStorage.read(objectKey)
        assertThat(stored).isNotNull()
        assertThat(fakeStorage.contentTypeOf(objectKey)).isEqualTo(SpecXlsxWriter.XLSX_CONTENT_TYPE)
        XSSFWorkbook(stored!!.inputStream()).use { workbook ->
            // 헤더 1줄 + 데이터 2줄.
            assertThat(workbook.getSheetAt(0).lastRowNum).isEqualTo(2)
        }

        val ticket = service.downloadTicket(projectId, userId)
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.downloadUrl).contains(objectKey)
    }

    @Test
    fun `새 revision으로 문구가 바뀌면 같은 행을 갱신하고 검증 상태를 보존한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        service.ingest(projectId, spec(revision = 1))

        // QA 런이 검증을 마친 상태를 만든다. 재적재가 이 값을 덮으면 이력이 사라진다.
        val verified = testCaseRepository.findByProjectIdAndSpecId(projectId, "scenario:aaa:1")!!
        testCaseRepository.save(verified.copy(verificationStatus = "VERIFIED", lastVerifiedBuildId = 42L))

        // 스텝 문구까지 바꾼다. 옛 멱등 키(씬+스텝)였다면 여기서 새 케이스가 생겼을 것이다.
        val result = service.ingest(
            projectId,
            spec(
                revision = 2,
                cases = listOf(
                    case(specId = "scenario:aaa:1", step = "상점에 들어간다", expectedValue = "상점 화면 진입(문구 수정)"),
                    case(specId = "scenario:aaa:2", step = "검 구매", expectedValue = "골드 차감 + 검 획득"),
                ).joinToString(","),
            ),
        )

        assertThat(result.created).isZero()
        assertThat(result.updated).isEqualTo(2)

        val cases = testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()
        assertThat(cases).hasSize(2)

        val reingested = testCaseRepository.findByProjectIdAndSpecId(projectId, "scenario:aaa:1")!!
        assertThat(reingested.step).isEqualTo("상점에 들어간다")
        assertThat(reingested.expectedValue).isEqualTo("상점 화면 진입(문구 수정)")
        assertThat(reingested.specRevision).isEqualTo(2)
        assertThat(reingested.verificationStatus).isEqualTo("VERIFIED")
        assertThat(reingested.lastVerifiedBuildId).isEqualTo(42L)
    }

    @Test
    fun `같은 revision이 다시 오면 아무것도 하지 않는다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        service.ingest(projectId, spec(revision = 7))
        fakeStorage.clear()

        val result = service.ingest(projectId, spec(revision = 7))

        assertThat(result.unchanged).isTrue()
        assertThat(result.created).isZero()
        assertThat(result.updated).isZero()
        // XLSX도 다시 쓰지 않는다 — 스킵의 값어치가 DB뿐이면 S3 왕복은 그대로 남는다.
        assertThat(fakeStorage.read(objectKeyOf(projectId))).isNull()
    }

    @Test
    fun `필수값이 빠진 케이스는 건너뛰고 나머지는 적재한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        val result = service.ingest(
            projectId,
            spec(
                cases = listOf(
                    case(specId = "scenario:bbb:1", step = "상점 입장", expectedValue = "상점 화면 진입"),
                    // 스텝이 비어 있다. 한 건이 잘못돼서 나머지가 통째로 막히면 보낸 쪽이 할 수 있는 게 없다.
                    case(specId = "scenario:bbb:2", step = "", expectedValue = "버려질 케이스"),
                ).joinToString(","),
            ),
        )

        assertThat(result.totalCases).isEqualTo(2)
        assertThat(result.created).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `JSON을 읽을 수 없으면 적재도 저장도 하지 않는다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        assertThatThrownBy {
            runBlocking { service.ingest(projectId, "{ not json".toByteArray()) }
        }.isInstanceOf(BadRequestException::class.java)

        assertThat(testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()).isEmpty()
        // S3가 마지막이라 앞 단계에서 멈추면 고아 객체가 남지 않는다.
        assertThat(fakeStorage.read(objectKeyOf(projectId))).isNull()
    }

    @Test
    fun `케이스가 하나도 없는 명세는 거부한다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        assertThatThrownBy {
            runBlocking { service.ingest(projectId, spec(cases = "")) }
        }.isInstanceOf(BadRequestException::class.java)

        assertThat(fakeStorage.read(objectKeyOf(projectId))).isNull()
    }

    /**
     * 적재가 실패하면 S3에도 아무것도 남지 않아야 한다. `scene`은 VARCHAR(200)이라 201자를 넣으면
     * INSERT가 깨지고, 그 지점은 이미 XLSX 생성이 끝난 뒤다 — 즉 "변환은 됐는데 커밋이 실패한"
     * 상황을 정확히 재현한다.
     */
    @Test
    fun `적재가 실패하면 XLSX도 남기지 않는다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()

        assertThatThrownBy {
            runBlocking {
                service.ingest(
                    projectId,
                    spec(
                        cases = case(
                            specId = "scenario:ccc:1",
                            scene = "가".repeat(201),
                            step = "상점 입장",
                            expectedValue = "진입",
                        )
                    ),
                )
            }
        }.isNotNull()

        assertThat(testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()).isEmpty()
        assertThat(fakeStorage.read(objectKeyOf(projectId))).isNull()
    }

    /**
     * XLSX 생성이 이벤트 루프에서 돌지 않는지 **실측**한다. 블로킹 작업이 `reactor-http-nio` 스레드에
     * 걸리면 그 스레드에 붙은 무관한 요청이 전부 멈춘다.
     */
    @Test
    fun `XLSX 생성은 이벤트 루프가 아닌 IO 디스패처에서 돈다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        val callerThread = Thread.currentThread().name

        service.ingest(projectId, spec())

        val executedOn = (xlsxWriter as RecordingWriter).executedOn
        logger.info("호출 스레드 = {} / XLSX 실행 스레드 = {}", callerThread, executedOn)

        assertThat(executedOn).isNotNull()
        assertThat(executedOn).doesNotStartWith("reactor-http-nio")
        // Dispatchers.IO 워커로 넘어갔다는 뜻. 호출 스레드에서 그대로 돌았다면 오프로딩이 없는 것이다.
        assertThat(executedOn).startsWith("DefaultDispatcher-worker")
        assertThat(executedOn).isNotEqualTo(callerThread)
    }

    @Test
    fun `비참여자에게는 다운로드 티켓을 주지 않는다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        service.ingest(projectId, spec())
        val outsiderId = newUser()

        assertThat(service.downloadTicket(projectId, outsiderId)).isNull()
    }

    @Test
    fun `아직 명세를 받지 않았으면 다운로드 티켓이 없다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()

        assertThat(service.downloadTicket(projectId, userId)).isNull()
    }

    private fun objectKeyOf(projectId: Long) = "projects/$projectId/test-case-spec/test-cases.xlsx"

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

    companion object {
        private val logger = LoggerFactory.getLogger(TestCaseSpecIngestIntegrationTest::class.java)
    }
}
