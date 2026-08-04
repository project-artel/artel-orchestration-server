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
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
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
 * QA WS의 knowledge 개별 생성·수정·삭제 인입 검증(ARTEL-188).
 *
 * 이 경로에서 중요한 것은 저장 자체보다 **두 가지 성질**이다.
 * 1. 범위가 Agent가 보낸 값이 아니라 런에서 나온다: `qaTryId → game_instance → project_id`.
 *    그래서 다른 프로젝트의 항목을 지목해도 닿지 않는다.
 * 2. 잘못된 프레임이 throw하지 않는다. throw하면 receive 파이프라인이 끊겨 프레임 하나가 QA 런
 *    전체를 실패시킨다. 거절은 ERROR 로그로만 남고 런은 RUNNING인 채여야 한다.
 *
 * 서비스 단위의 검증(필드별 규칙, 임베딩 무효화)은 [KnowledgeIntegrationTest]와
 * [KnowledgeEmbeddingBackfillIntegrationTest]가 맡는다. 여기서는 라우터가 그 서비스에 올바른
 * 범위를 넘기고 결과를 값으로 처리하는지만 본다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeMutationInboundIntegrationTest {

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
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

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다
     * (IssueIntegrationTest와 같은 이유). knowledge는 project를 논리참조하므로 FK로 막지는
     * 않지만, 남기면 다음 테스트의 프로젝트 스코프 조회에 섞이므로 함께 비운다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
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

    @Test
    fun `CREATE 프레임은 그 런의 프로젝트에 QA 출처로 저장한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"낙하 데미지","description":"5m부터 1당 2"}""")

        val stored = knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(run.projectId).toList()
        assertThat(stored).hasSize(1)
        assertThat(stored.single().source).isEqualTo("QA")
        assertThat(stored.single().sourceId).isEqualTo(run.qaTryId)
        assertThat(stored.single().summary).isEqualTo("낙하 데미지")
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `UPDATE 프레임은 항목을 고치고 출처를 남긴다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val id = givenKnowledge(run.projectId, summary = "옛 요약")

        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", """{"knowledge_id":"$id","summary":"새 요약"}""")

        val row = knowledgeRepository.findById(id)!!
        assertThat(row.summary).isEqualTo("새 요약")
        assertThat(row.updatedByQaTryId).isEqualTo(run.qaTryId)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `DELETE 프레임은 하드 삭제가 아니라 표식을 남긴다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val id = givenKnowledge(run.projectId)

        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$id"}""")

        val row = knowledgeRepository.findById(id)
        assertThat(row).describedAs("행이 사라졌다면 하드 삭제다").isNotNull()
        assertThat(row!!.deletedAt).isNotNull()
        assertThat(row.deletedByQaTryId).isEqualTo(run.qaTryId)
        // 읽기 경로에서는 사라진다.
        assertThat(knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(run.projectId).toList()).isEmpty()
    }

    /** 범위가 런에서 나오므로, 다른 프로젝트의 id를 지목해도 그 항목에 닿지 않는다. */
    @Test
    fun `다른 프로젝트의 항목은 지목해도 지워지지 않는다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val otherProject = seedRunningQaTry().projectId
        val victim = givenKnowledge(otherProject, summary = "남의 지식")

        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$victim"}""")

        assertThat(knowledgeRepository.findById(victim)!!.deletedAt).isNull()
        assertThat(errorLogs(run.qaTryId)).hasSize(1)
        assertThat(errorLogs(run.qaTryId).single().message).contains("not found in project")
    }

    @Test
    fun `잘못된 프레임은 ERROR로 떨어지고 런은 계속된다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        // 무효 tag / 대상 id 없음 / 숫자가 아닌 id — 어느 것도 throw하면 안 된다.
        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"NOPE","summary":"s","description":"d"}""")
        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", """{"summary":"s"}""")
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"abc"}""")

        assertThat(knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(run.projectId).toList()).isEmpty()
        assertThat(errorLogs(run.qaTryId)).hasSize(3)
        // 런이 살아 있어야 한다. 여기가 FAILED면 프레임 하나가 QA 런을 죽인 것이다.
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    @Test
    fun `배치 인입 KNOWLEDGE와 공존한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        deliver(
            run.qaTryId,
            "KNOWLEDGE",
            """{"source":"qa","game_context":[{"tag":"UI","summary":"체력바","description":"좌상단"}]}"""
        )
        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"낙하","description":"5m부터"}""")

        val stored = knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(run.projectId).toList()
        assertThat(stored.map { it.summary }).containsExactlyInAnyOrder("체력바", "낙하")
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    // --- helpers ---

    private suspend fun deliver(qaTryId: Long, type: String, payload: String) {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = Instant.parse("2026-07-29T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

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

    private suspend fun seedRunningQaTry(): RunningQaTry {
        val owner = signIn(UUID.randomUUID().toString().take(8))
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "knowledge-mutation", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!, payload = Json.of("{}"))
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
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
                startedAt = now
            )
        )!!
        return RunningQaTry(qaTryId = qaTry.id!!, projectId = project.id!!)
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
