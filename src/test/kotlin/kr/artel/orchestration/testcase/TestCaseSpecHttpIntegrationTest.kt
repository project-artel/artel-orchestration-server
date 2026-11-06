package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.config.InternalApiServer
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

/** 명세 한 벌. `revision`을 바꿔야 재적재가 스킵되지 않는다(같은 판은 통째로 건너뛴다). */
private fun specJson(revision: Int = 1): ByteArray = """
    {
      "id": "spec-doc-1",
      "revision": $revision,
      "cases": [
        { "schema_version": "test-case.v1",
          "spec": { "scene": "TitleScene", "precondition": "로비에 있음",
                    "step": "상점 입장", "expected_value": "상점 화면 진입", "status": "ready" },
          "metadata": { "source": { "spec_id": "scenario:http:1" } } },
        { "schema_version": "test-case.v1",
          "spec": { "scene": "TitleScene", "precondition": "골드 10 이상",
                    "step": "검 구매", "expected_value": "골드 차감 + 검 획득", "status": "ready" },
          "metadata": { "source": { "spec_id": "scenario:http:2" } } }
      ],
      "created_at": "2026-08-11T00:00:00Z"
    }
""".trimIndent().toByteArray()

/**
 * Agent → Orchestration 명세 JSON 수신 경로의 HTTP 통합 테스트.
 *
 * 서비스 계층 검증([TestCaseSpecIngestIntegrationTest])과 달리 **실제 HTTP로** 찌른다.
 * 여기서만 확인되는 것: 인증 없이 통과하는지(서버-투-서버 경로라 permitAll), 원문 바이트가
 * JSON 본문이 그대로 실려 오는지, 오류가 약속한 상태 코드로 매핑되는지.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestCaseSpecHttpIntegrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    @Autowired private lateinit var internalApiServer: InternalApiServer

    private val client: WebClient get() = WebClient.create("http://localhost:$port")

    /**
     * 명세 수신은 무인증 내부 경로라 공개 포트가 아니라 내부 포트에만 있다(ARTEL-266).
     * 같은 파일의 다운로드 테스트는 엔드유저 경로라 [client]를 그대로 쓴다 — 둘을 한 클라이언트로
     * 합치면 그쪽 401 단언이 404로 바뀌어 조용히 무의미해진다.
     */
    private val internalClient: WebClient
        get() = WebClient.create("http://localhost:${internalApiServer.port}")

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    @Test
    fun `Agent는 인증 없이 명세를 올리고 적재 결과를 돌려받는다`(): Unit = runBlocking {
        val projectId = newProject()

        val body = post(projectId, specJson())

        assertThat(body["totalCases"].asInt()).isEqualTo(2)
        assertThat(body["created"].asInt()).isEqualTo(2)
        assertThat(body["updated"].asInt()).isZero()

        val cases = testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()
        assertThat(cases.map { it.step }).containsExactlyInAnyOrder("상점 입장", "검 구매")
        assertThat(fakeStorage.bytesOf("projects/$projectId/test-case-spec/test-cases.xlsx")).isNotNull()
    }

    @Test
    fun `재전송해도 중복이 쌓이지 않는다`(): Unit = runBlocking {
        val projectId = newProject()
        post(projectId, specJson(revision = 1))

        // 같은 revision은 통째로 스킵되므로, 갱신 경로를 보려면 판을 올려 보낸다.
        val body = post(projectId, specJson(revision = 2))

        assertThat(body["created"].asInt()).isZero()
        assertThat(body["updated"].asInt()).isEqualTo(2)
        assertThat(testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()).hasSize(2)
    }

    @Test
    fun `없는 프로젝트로 보내면 404다`(): Unit = runBlocking {
        val error = postExpectingError(999_999L, specJson())

        assertThat(error.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `JSON이 아니면 400이고 아무것도 남지 않는다`(): Unit = runBlocking {
        val projectId = newProject()

        val error = postExpectingError(projectId, "{ not json".toByteArray())

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(testCaseRepository.findByProjectIdOrderByIdDesc(projectId).toList()).isEmpty()
        assertThat(fakeStorage.bytesOf("projects/$projectId/test-case-spec/test-cases.xlsx")).isNull()
    }

    @Test
    fun `빈 본문은 400이다`(): Unit = runBlocking {
        val projectId = newProject()

        val error = postExpectingError(projectId, ByteArray(0))

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /** 사용자 다운로드는 이 경로와 달리 인증 대상이다. 토큰 없이 부르면 401이어야 한다. */
    @Test
    fun `다운로드는 인증 없이 열리지 않는다`(): Unit = runBlocking {
        val projectId = newProject()
        post(projectId, specJson())

        val error = runCatching {
            client.get().uri("/api/projects/$projectId/test-case-spec/download")
                .retrieve().bodyToMono(String::class.java).block()
        }.exceptionOrNull() as WebClientResponseException

        assertThat(error.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun post(projectId: Long, body: ByteArray): JsonNode =
        internalClient.post().uri("/internal/test-case-spec/$projectId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block()!!

    private fun postExpectingError(projectId: Long, body: ByteArray): WebClientResponseException =
        runCatching { post(projectId, body) }.exceptionOrNull() as WebClientResponseException

    /** 명세 수신은 멤버십을 보지 않지만, 프로젝트 자체는 실재해야 한다. */
    private suspend fun newProject(): Long {
        val now = Instant.now()
        val userId = appUserRepository.save(
            testAppUser("http-spec-user", now)
        ).id!!
        val projectId = projectRepository.save(
            ProjectEntity(name = "http-spec-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        return projectId
    }
}
