package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.repository.QaKnowledgeWriteRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * 지식 쓰기의 멱등 검증(ARTEL-364).
 *
 * 막는 것이 둘이고 성질이 다르므로 검증도 둘로 갈린다.
 *
 * - **재전송** — 같은 프레임이 두 번 온다. 다섯 타입 전부 한 번만 적용되고, 두 번째도 성공 응답과
 *   **첫 번째와 같은 id**를 받아야 한다. 거절로 답하면 ARTEL-367의 재시도가 실패로 읽힌다.
 * - **같은 사실의 재기록** — 다른 messageId로 오는 새 호출이다. CREATE에만 건다. 나머지 넷은
 *   지금의 거절("not found", "already linked")이 맞는 답이므로 건드리지 않는다.
 *
 * 여기서 응답 프레임까지 보지 않는 것은 [KnowledgeWriteResultRouterIntegrationTest]가 그 계약을
 * 이미 고정하기 때문이다. 이 스위트는 **몇 행이 남았고 어떤 id로 답했나**만 본다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeWriteIdempotencyIntegrationTest {

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
    @Autowired private lateinit var writeRepository: QaKnowledgeWriteRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        writeRepository.deleteAll()
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------- 재전송

    @Test
    fun `같은 CREATE 프레임을 두 번 받아도 항목은 하나다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val frame = UUID.randomUUID().toString()
        val payload = """{"tag":"RULE","summary":"낙하 데미지","description":"5m부터 1당 2"}"""

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload, messageId = frame)
        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload, messageId = frame)

        assertThat(alive(run.projectId)).hasSize(1)
        // 원장도 한 줄이다. 두 줄이면 유일 제약이 도는 대신 서비스가 두 번 적용한 것이다.
        assertThat(writeRepository.findAll().toList()).hasSize(1)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `재전송된 UPDATE와 DELETE는 한 번만 적용된다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val id = givenKnowledge(run.projectId, summary = "옛 규칙")
        val updateFrame = UUID.randomUUID().toString()
        val deleteFrame = UUID.randomUUID().toString()

        val update = """{"knowledge_id":"$id","summary":"새 규칙"}"""
        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", update, messageId = updateFrame)
        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", update, messageId = updateFrame)

        // 두 번 적용됐다면 content 버전이 한 번 더 올랐을 것이다.
        assertThat(knowledgeRepository.findById(id)!!.version).isEqualTo(2)

        val delete = """{"knowledge_id":"$id"}"""
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", delete, messageId = deleteFrame)
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", delete, messageId = deleteFrame)

        // 재전송이 "이미 지워졌다"는 거절로 떨어지면 안 된다. ARTEL-367의 재시도가 그것을 실패로
        // 읽고, 확인 못 받은 삭제를 되살릴 방법이 없어진다.
        assertThat(errorLogs(run.qaTryId)).isEmpty()
        assertThat(writeRepository.findAll().toList()).hasSize(2)
    }

    @Test
    fun `재전송된 LINK와 UNLINK는 한 번만 적용된다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val from = givenKnowledge(run.projectId, "왼쪽")
        val to = givenKnowledge(run.projectId, "오른쪽")
        val linkFrame = UUID.randomUUID().toString()
        val unlinkFrame = UUID.randomUUID().toString()
        val link = """{"from_knowledge_id":"$from","to_knowledge_id":"$to","relation":"REFINES","note":"확인함"}"""

        deliver(run.qaTryId, "KNOWLEDGE_LINK", link, messageId = linkFrame)
        deliver(run.qaTryId, "KNOWLEDGE_LINK", link, messageId = linkFrame)

        assertThat(edgeRepository.findAll().toList()).hasSize(1)
        assertThat(errorLogs(run.qaTryId)).isEmpty()

        val unlink = """{"from_knowledge_id":"$from","to_knowledge_id":"$to","relation":"REFINES"}"""
        deliver(run.qaTryId, "KNOWLEDGE_UNLINK", unlink, messageId = unlinkFrame)
        deliver(run.qaTryId, "KNOWLEDGE_UNLINK", unlink, messageId = unlinkFrame)

        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /**
     * 같은 messageId를 다른 뜻으로 다시 쓰는 것은 프로토콜 위반이다. 첫 번째 결과를 조용히
     * 돌려주면 두 번째 요청이 한 적 없는 일을 했다고 답하게 된다.
     */
    @Test
    fun `같은 messageId를 다른 타입으로 다시 쓰면 거절한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val id = givenKnowledge(run.projectId)
        val frame = UUID.randomUUID().toString()

        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", """{"knowledge_id":"$id","summary":"고침"}""", messageId = frame)
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$id"}""", messageId = frame)

        assertThat(knowledgeRepository.findById(id)!!.deletedAt)
            .describedAs("두 번째 프레임이 적용됐다면 지워져 있다")
            .isNull()
        assertThat(errorLogs(run.qaTryId).single().message).contains("already used for KNOWLEDGE_UPDATE")
    }

    // ------------------------------------------------- 같은 사실의 재기록

    @Test
    fun `같은 내용을 다른 프레임으로 다시 기록해도 항목은 하나다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val payload = """{"tag":"RULE","summary":"낙하 데미지","description":"5m부터 1당 2"}"""

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload)
        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload)

        assertThat(alive(run.projectId)).hasSize(1)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /**
     * 운영 스코프는 `scope_id`가 NULL이다. 유니크 인덱스에 `NULLS NOT DISTINCT`가 없으면 NULL끼리는
     * 서로 다른 값이라 **운영 행끼리는 절대 충돌하지 않는다** — 위 케이스가 조용히 통과해 버린다.
     * 이 테스트가 그 함정을 지킨다.
     */
    @Test
    fun `운영 스코프에서도 내용 키가 실제로 걸린다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeScopeId = null)
        val payload = """{"tag":"UI","summary":"체력바는 좌상단","description":"항상 보인다"}"""

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload)
        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload)

        val rows = knowledgeRepository.findVisible(run.projectId, null, null, null).toList()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().scopeId).isNull()
    }

    /** 스코프 격리가 이긴다. 실험 스코프가 운영에 있는 것과 같은 사실을 자기 쪽에 쓰는 것은 정상이다. */
    @Test
    fun `다른 스코프의 같은 내용은 막지 않는다`(): Unit = runBlocking {
        val production = seedRunningQaTry()
        val payload = """{"tag":"RULE","summary":"같은 사실","description":"같은 설명"}"""
        deliver(production.qaTryId, "KNOWLEDGE_CREATE", payload)

        val scoped = seedRunningQaTry(knowledgeScopeId = 9_001L, projectId = production.projectId)
        deliver(scoped.qaTryId, "KNOWLEDGE_CREATE", payload)

        assertThat(knowledgeRepository.findVisible(production.projectId, null, null, null).toList()).hasSize(1)
        assertThat(knowledgeRepository.findVisible(production.projectId, 9_001L, null, null).toList()).hasSize(2)
        assertThat(errorLogs(scoped.qaTryId)).isEmpty()
    }

    /** 지웠다 같은 사실을 다시 쓰는 것은 정당한 흐름이다. 인덱스가 `deleted_at IS NULL`을 거는 이유다. */
    @Test
    fun `지운 뒤 같은 내용을 다시 쓸 수 있다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val payload = """{"tag":"RULE","summary":"되살릴 사실","description":"설명"}"""

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload)
        val first = alive(run.projectId).single().id!!
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$first"}""")
        deliver(run.qaTryId, "KNOWLEDGE_CREATE", payload)

        val living = alive(run.projectId)
        assertThat(living).hasSize(1)
        assertThat(living.single().id).isNotEqualTo(first)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    // --- helpers ---

    private suspend fun deliver(
        qaTryId: Long,
        type: String,
        payload: String,
        messageId: String = UUID.randomUUID().toString()
    ) {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = type,
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = Instant.parse("2026-08-13T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun alive(projectId: Long) =
        knowledgeRepository.findVisible(projectId, null, null, null).toList()

    private suspend fun errorLogs(qaTryId: Long) =
        qaLogRepository.findAll().toList().filter { it.qaTryId == qaTryId && it.type == "ERROR" }

    private suspend fun givenKnowledge(projectId: Long, summary: String = "요약"): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "RULE",
                summary = summary,
                description = "설명"
            )
        ).id!!

    private suspend fun seedRunningQaTry(
        knowledgeScopeId: Long? = null,
        projectId: Long? = null
    ): RunningQaTry {
        val owner = signIn(UUID.randomUUID().toString().take(8))
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectId ?: projectRepository.save(
            ProjectEntity(name = "knowledge-idempotency", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project))!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "RUNNING",
                knowledgeScopeId = knowledgeScopeId,
                runConfig = Json.of("{}"),
                startedAt = now
            )
        )!!
        return RunningQaTry(qaTryId = qaTry.id!!, projectId = project)
    }

    private suspend fun signIn(seed: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "user-$seed@example.com"
            )
        )!!

    private data class RunningQaTry(val qaTryId: Long, val projectId: Long)
}
