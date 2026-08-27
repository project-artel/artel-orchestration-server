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
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
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
    @Autowired private lateinit var anchorRepository: KnowledgeAnchorRepository
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
        // knowledge_anchor는 FK가 없는 논리참조라(V55) 따로 비운다.
        anchorRepository.deleteAll()
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

        val stored = knowledgeRepository.findVisible(run.projectId, null, null, null).toList()
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
        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
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

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
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

        val stored = knowledgeRepository.findVisible(run.projectId, null, null, null).toList()
        assertThat(stored.map { it.summary }).containsExactlyInAnyOrder("체력바", "낙하")
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    // ------------------------------------------ 스코프와 knowledge_mode (ARTEL-256)

    /**
     * 스코프도 프로젝트와 같이 **런에서 나온다.** Agent가 프레임으로 스코프를 지목할 수 있으면
     * 격리가 프레임 하나로 뚫리고, 그렇게 뚫린 실험은 결과가 그럴듯해서 아무도 못 알아챈다.
     */
    @Test
    fun `스코프 런의 쓰기는 그 스코프로 간다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeScopeId = 5_001L)

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"실험 지식","description":"d"}""")
        deliver(
            run.qaTryId,
            "KNOWLEDGE",
            """{"source":"qa","game_context":[{"tag":"UI","summary":"실험 배치","description":"d"}]}"""
        )

        // 운영 조회에는 잡히지 않는다.
        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
        val inScope = knowledgeRepository.findVisible(run.projectId, 5_001L, null, null).toList()
        assertThat(inScope.map { it.summary }).containsExactlyInAnyOrder("실험 지식", "실험 배치")
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /** 스코프 런의 삭제는 운영 행을 건드리지 않고 툼스톤 그림자를 남긴다. */
    @Test
    fun `스코프 런의 DELETE는 운영 행을 건드리지 않는다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeScopeId = 5_002L)
        val baseline = givenKnowledge(run.projectId, summary = "운영 지식")

        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$baseline"}""")

        assertThat(knowledgeRepository.findById(baseline)!!.deletedAt).isNull()
        assertThat(knowledgeRepository.findVisible(run.projectId, 5_002L, null, null).toList()).isEmpty()
        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).hasSize(1)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /**
     * `frozen`은 쓰기 프레임 전부를 거부한다 — 개별 변이든 배치 인입이든.
     *
     * **거부가 런을 죽이면 안 된다.** 거절은 정상 동작이고, 여기서 예외가 WS 수신 체인 밖으로 나가면
     * 소켓이 닫혀 그 arm의 런이 통째로 실패한다.
     */
    @Test
    fun `frozen 런의 쓰기는 거부되고 런은 계속된다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeMode = "frozen")

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"쓰면 안 됨","description":"d"}""")
        deliver(
            run.qaTryId,
            "KNOWLEDGE",
            """{"source":"qa","game_context":[{"tag":"UI","summary":"이것도 안 됨","description":"d"}]}"""
        )
        val victim = givenKnowledge(run.projectId, summary = "그대로")
        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", """{"knowledge_id":"$victim","summary":"바뀌면 안 됨"}""")
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$victim"}""")

        val rows = knowledgeRepository.findVisible(run.projectId, null, null, null).toList()
        assertThat(rows.map { it.summary }).containsExactly("그대로")
        assertThat(errorLogs(run.qaTryId)).hasSize(4)
        assertThat(errorLogs(run.qaTryId)).allMatch { it.message!!.contains("knowledge_mode=frozen") }
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /** `off`는 읽기까지 막으므로 쓰기도 당연히 막힌다. */
    @Test
    fun `off 런의 쓰기도 거부된다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeMode = "off")

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"쓰면 안 됨","description":"d"}""")

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
        assertThat(errorLogs(run.qaTryId).single().message).contains("knowledge_mode=off")
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /**
     * 회귀 방어. `run_config`에 `knowledge_mode`가 없는 런은 이 기능 이전의 런과 구버전 Agent가
     * 붙은 런이다. 둘 다 지금까지처럼 읽고 써야 한다 — 모드를 모르는 런이 실패하면 실험의 공백이
     * 아니라 장애다.
     */
    @Test
    fun `run_config에 모드가 없으면 지금까지처럼 쓴다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeMode = null)

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"저장된다","description":"d"}""")

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).hasSize(1)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    // ------------------------------------------------------------- `anchor` (ARTEL-591)

    /**
     * 프레임의 필드 이름을 못박는다. Agent 쪽(ARTEL-592)이 맞춰야 하는 계약이 이것이다 —
     * `scene_name`과 `screen_id`이고, 둘 다 선택이며 `screen_id`는 문자열이다(`knowledge_id`와
     * 같은 이유로 64비트 정밀도 손실을 피한다).
     */
    @Test
    fun `CREATE 프레임의 anchor 필드가 저장된다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        deliver(
            run.qaTryId,
            "KNOWLEDGE_CREATE",
            """{"tag":"CONTROL","summary":"ESC는 무반응","description":"전투 중","scene_name":"Combat","screen_id":"4242"}"""
        )

        val stored = knowledgeRepository.findVisible(run.projectId, null, null, null).toList().single()
        val anchors = anchorRepository.findAll().toList().filter { it.knowledgeId == stored.id }
        assertThat(anchors).hasSize(1)
        assertThat(anchors.single().sceneName).isEqualTo("Combat")
        assertThat(anchors.single().screenId).isEqualTo(4_242L)
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /**
     * 회귀 방어. `anchor` 를 싣지 않은 프레임은 이 기능 이전과 완전히 같아야 한다 — `anchor` 없는 지식이
     * 게임 전체의 사실이고 그것이 기본값이다.
     */
    @Test
    fun `anchor 를 싣지 않은 CREATE 프레임은 지금까지와 같다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"낙하 데미지","description":"5m부터"}""")

        val stored = knowledgeRepository.findVisible(run.projectId, null, null, null).toList().single()
        assertThat(stored.summary).isEqualTo("낙하 데미지")
        assertThat(anchorRepository.findAll().toList().filter { it.knowledgeId == stored.id }).isEmpty()
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    /**
     * 화면은 씬 안에 산다. 씬 없는 화면 `anchor` 는 저장해 봐야 되짚을 수 없으므로 거절하고, 그때
     * **지식도 저장하지 않는다** — `anchor` 만 조용히 버리면 Agent는 화면 지식을 적었다고 믿는다.
     * 그래도 throw는 아니다: 프레임 하나가 QA 런을 죽이지 못한다.
     */
    @Test
    fun `씬 없이 화면만 실은 CREATE 프레임은 ERROR로 떨어진다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        deliver(
            run.qaTryId,
            "KNOWLEDGE_CREATE",
            """{"tag":"RULE","summary":"s","description":"d","screen_id":"77"}"""
        )

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
        assertThat(anchorRepository.findAll().toList()).isEmpty()
        assertThat(errorLogs(run.qaTryId).single().message).contains("scene_name")
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /** `anchor` 도 스코프를 따로 지지 않는다 — knowledge 행의 스코프가 곧 그 `anchor` 의 스코프다(V55). */
    @Test
    fun `스코프 런이 만든 anchor 는 그 스코프의 지식에 달린다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeScopeId = 5_003L)

        deliver(
            run.qaTryId,
            "KNOWLEDGE_CREATE",
            """{"tag":"RULE","summary":"실험 지식","description":"d","scene_name":"Town"}"""
        )

        val inScope = knowledgeRepository.findVisible(run.projectId, 5_003L, null, null).toList().single()
        val visible = anchorRepository.findVisibleFor(listOf(inScope.id!!), 5_003L).toList()
        assertThat(visible.map { it.sceneName }).containsExactly("Town")
        // 운영 런에는 그 지식이 없으므로 그 `anchor` 도 없다.
        assertThat(anchorRepository.findVisibleFor(listOf(inScope.id!!), null).toList()).isEmpty()
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

    private suspend fun seedRunningQaTry(
        knowledgeScopeId: Long? = null,
        knowledgeMode: String? = null
    ): RunningQaTry {
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
            TestScenarioEntity(projectId = project.id!!)
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
                knowledgeScopeId = knowledgeScopeId,
                // 모드가 null인 런은 이 기능 이전의 런과 구버전 Agent가 붙은 런을 재현한다.
                runConfig = knowledgeMode
                    ?.let { Json.of("""{"knowledge_mode":"$it"}""") }
                    ?: Json.of("{}"),
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
