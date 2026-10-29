package kr.artel.orchestration.testcase.contract

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.settings.config.AgentServerProperties
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testcase.dto.SceneExit
import org.springframework.r2dbc.core.DatabaseClient
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 계약 정리: 글만 있는 TC 에만 보내고, 한 번 뽑은 것은 문구가 바뀔 때만 다시 보낸다. agent 는 가짜다.
 */
@ActiveProfiles("test")
@SpringBootTest
class CaseContractServiceIntegrationTest {

    class FakeAgent(private val mapper: ObjectMapper) : AgentCaseContractClient(AgentServerProperties()) {
        val asked = mutableListOf<List<Long>>()
        var arrivals: Map<Long, String> = emptyMap()
        var slowMs = 0L
        override suspend fun extract(request: CaseContractRequest): CaseContractResponse {
            if (slowMs > 0) kotlinx.coroutines.delay(slowMs)
            asked += request.cases.map { it.id }
            return CaseContractResponse(
                terms = mapper.readTree("""[{"name":"검","kind":"has","meaning":"","start":"없음"}]"""),
                startState = mapOf("검" to "0", "낮밤" to "낮"),
                contracts = request.cases.map {
                    CaseContractItem(
                        id = it.id,
                        condition = mapper.readTree("""{"kind":"test","left":"검","operator":"==","right":"1","context":"static","offset":0,"subjectLost":null}"""),
                        stateAfter = "늑대_처치=1",
                        assumedBefore = "",
                        arrivesAt = arrivals[it.id].orEmpty(),
                    )
                },
            )
        }

        var observedPatches: List<StatementPatch> = emptyList()
        val observedLines = mutableListOf<String>()
        override suspend fun observed(request: ObservedContractRequest): StatementResponse {
            observedLines += request.observed
            return StatementResponse(observedPatches)
        }

        var patches: List<StatementPatch> = emptyList()
        val heard = mutableListOf<String>()
        override suspend fun statement(request: StatementRequest): StatementResponse {
            heard += request.said
            return StatementResponse(patches)
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary
        fun fakeAgent(mapper: ObjectMapper): AgentCaseContractClient = FakeAgent(mapper)
    }

    @Autowired private lateinit var service: CaseContractService
    @Autowired private lateinit var agent: AgentCaseContractClient
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var cases: TestCaseRepository

    private fun case(projectId: Long, step: String, capabilityKey: String? = null, condition: String? = null) =
        TestCaseEntity(
            projectId = projectId, scene = "숲", step = step, expectedValue = "늑대가 쓰러진다.",
            capabilityKey = capabilityKey, condition = condition?.let { Json.of(it) },
        )

    @Test
    fun `only plain-language cases are sent, once, and again when their text changes`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "contract", genre = "RPG", createdAt = now, updatedAt = now))
        val plain = cases.save(case(project.id!!, "늑대를 공격한다."))
        cases.save(case(project.id!!, "지도에서 온 것", capabilityKey = "cap-1"))
        cases.save(case(project.id!!, "조건을 직접 적은 것", condition = """{"kind":"always"}"""))
        val fake = agent as FakeAgent
        fake.asked.clear()

        val first = service.refresh(project.id!!)
        val second = service.refresh(project.id!!)
        cases.save(cases.findById(plain.id!!)!!.copy(step = "늑대를 검으로 공격한다."))
        val third = service.refresh(project.id!!)

        assertThat(fake.asked).containsExactly(listOf(plain.id), listOf(plain.id))
        assertThat(first.written).isEqualTo(1)
        assertThat(second.asked).isEqualTo(0)
        assertThat(third.written).isEqualTo(1)
        val saved = cases.findByProjectIdOrderByIdAsc(project.id!!).toList().first { it.id == plain.id }
        assertThat(saved.condition!!.asString()).contains("\"left\": \"검\"")
        assertThat(saved.metadata.asString()).contains("늑대_처치=1").contains("\"source\": \"ai\"")
        assertThat(service.startState(project.id!!)).containsEntry("낮밤", "낮").containsEntry("검", "0")
    }

    @Test
    fun `an answer moves into the contract and marks it as the user's`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "answer", genre = "RPG", createdAt = now, updatedAt = now))
        val wolf = cases.save(
            case(project.id!!, "늑대를 공격한다.").copy(
                metadata = Json.of("""{"source":{"assumed_before":"검 == 1"},"contract":{"source":"ai","hash":"x"}}"""),
            )
        )
        val sword = cases.save(case(project.id!!, "검을 받는다.").copy(metadata = Json.of("""{"source":{"state_after":"검=1"}}""")))
        val slime = cases.save(
            case(project.id!!, "슬라임을 잡는다.", condition = """{"kind":"test","left":"검_장착","operator":"==","right":"1"}""")
        )

        service.applyAnswer(project.id!!, CaseContractService.ContractAnswerRef(wolf.id!!, "검", "==", "1"), "yes-by:${sword.id}")
        service.applyAnswer(project.id!!, CaseContractService.ContractAnswerRef(slime.id!!, "검_장착", "==", "1"), "no")
        service.applyAnswer(project.id!!, CaseContractService.ContractAnswerRef(wolf.id!!, "낮밤", "==", "밤"), "start")

        val wolfNow = cases.findById(wolf.id!!)!!
        assertThat(wolfNow.condition!!.asString()).contains("\"left\": \"검\"")
        assertThat(wolfNow.metadata.asString()).contains("\"source\": \"user\"").doesNotContain("검 == 1")
        assertThat(cases.findById(slime.id!!)!!.condition).isNull()
        assertThat(service.startState(project.id!!)).containsEntry("낮밤", "밤")
    }

    @Test
    fun `what the user says in the chat goes into the contract only for a contract project`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "said", genre = "RPG", createdAt = now, updatedAt = now))
        val wolf = cases.save(case(project.id!!, "늑대를 공격한다."))
        val fake = agent as FakeAgent
        fake.heard.clear()

        // 용어집이 없으면(계약을 쓰지 않는 프로젝트) 모델을 부르지 않는다.
        assertThat(service.applyStatement(project.id!!, "늑대는 검이 있어야 잡혀", "")).isEqualTo(0)
        assertThat(fake.heard).isEmpty()

        service.refresh(project.id!!)
        fake.patches = listOf(
            StatementPatch(tcId = wolf.id, op = "needs", name = "검", value = "1"),
            StatementPatch(op = "start", name = "낮밤", value = "밤"),
        )
        val applied = service.applyStatement(project.id!!, "늑대는 검이 있어야 잡혀. 그리고 게임은 밤에 시작해", "")

        assertThat(applied).isEqualTo(2)
        assertThat(cases.findById(wolf.id!!)!!.metadata.asString()).contains("\"source\": \"user\"")
        assertThat(service.startState(project.id!!)).containsEntry("낮밤", "밤")
    }

    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var testCases: TestCaseService
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var builds: GameBuildRepository

    @Test
    fun `QA 가 본 씬 출구는 TC 씬 이름으로 옮겨 저작 목록에 실린다`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "observed", genre = "RPG", createdAt = now, updatedAt = now))
        val user = users.save(testAppUser("observer", now))
        members.save(ProjectMemberEntity(projectId = project.id!!, appUserId = user.id!!, role = "OWNER", createdAt = now))
        suspend fun seen(scene: String, step: String, observed: String) = cases.save(
            TestCaseEntity(projectId = project.id!!, scene = scene, step = step, expectedValue = "-", metadata = Json.of("""{"observed":$observed}""")),
        )
        val start = seen("타이틀", "시작하기를 누른다", """{"scene":"TitleScene","arrives_at":"SquareScene","input":"click:Canvas/Start"}""")
        seen("광장", "상점에 들어간다", """{"scene":"SquareScene"}""")
        val forest = seen("숲", "늑대를 공격한다", """{"scene":"ForestScene"}""")
        val older = builds.save(GameBuildEntity(projectId = project.id!!, version = "0.1.0", createdAt = now.minusSeconds(60), updatedAt = now))
        val current = builds.save(GameBuildEntity(projectId = project.id!!, version = "0.2.0", createdAt = now, updatedAt = now))
        db.sql("INSERT INTO case_glossary (project_id, exits) VALUES (:p, CAST(:e AS jsonb))")
            .bind("p", project.id!!)
            .bind("e", """[{"from":"SquareScene","to":"ForestScene","by":"key:W","build_id":${current.id}},{"from":"SquareScene","to":"CaveScene","by":"key:S","build_id":${older.id}}]""")
            .fetch().rowsUpdated().awaitSingle()

        val sent = testCases.getAuthoringCases(project.id!!, user.id!!).associateBy { it.id }

        assertThat(sent[start.id]!!.stateAfter).containsEntry("scene", "광장")
        assertThat(sent[start.id]!!.input).isEqualTo("click:Canvas/Start")
        val square = sent.values.single { it.scene == "광장" }
        // 짝이 없는 게임 씬(CaveScene)은 이름 그대로 둔다 — 지우면 그 길이 없는 것으로 읽힌다.
        // 앞 버전에서만 본 길은 버리지 않고 표시해 보낸다.
        assertThat(square.exits).containsExactlyInAnyOrder(
            SceneExit("숲", "key:W"),
            SceneExit("CaveScene", "key:S (seen by QA in an earlier build)"),
        )
        assertThat(sent[forest.id]!!.exits).isEmpty()
    }

    /** 저작을 여는 요청이 기다리다 끊겨도 정리는 끝까지 가서 계약을 쓴다. */
    @Test
    fun `a refresh keeps going when the waiting request is cancelled`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "detached", genre = "RPG", createdAt = now, updatedAt = now))
        val plain = cases.save(case(project.id!!, "늑대를 공격한다."))
        val fake = agent as FakeAgent
        fake.slowMs = 1_000
        try {
            val waited = kotlinx.coroutines.withTimeoutOrNull(100) { service.refreshInBackground(project.id!!).await() }
            assertThat(waited).isNull()
            service.refreshInBackground(project.id!!).await()
            assertThat(cases.findById(plain.id!!)!!.condition).isNotNull
        } finally {
            fake.slowMs = 0
        }
    }

    /** QA 가 본 값의 변화는 '남기는 것' 으로만, 출처 observed 로 들어간다. 요구는 받아도 버린다. */
    @Test
    fun `observed changes add only what a passed case leaves`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "observed-leaves", genre = "RPG", createdAt = now, updatedAt = now))
        val gift = cases.save(case(project.id!!, "할머니한테 말을 건다."))
        service.refresh(project.id!!)
        val fake = agent as FakeAgent
        fake.observedPatches = listOf(
            StatementPatch(gift.id, "leaves", "등불", "1"),
            StatementPatch(gift.id, "needs", "검", "1"),
        )

        val applied = service.applyObserved(project.id!!, mapOf(gift.id!! to mapOf("Inventory.hasLamp" to "false→true")))

        assertThat(applied).isEqualTo(1)
        assertThat(fake.observedLines.last()).contains("Inventory.hasLamp false→true")
        val meta = ObjectMapper().readTree(cases.findById(gift.id!!)!!.metadata.asString())
        assertThat(meta.path("source").path("state_after").asText()).contains("등불=1")
        assertThat(meta.path("contract").path("source").asText()).isEqualTo("observed")
    }

    /** 씬 전이 TC 는 도착 화면이 표식으로 남고, 그 화면에 서 있는 TC 들의 출구에 "그 TC 를 하면 간다" 로 실린다. */
    @Test
    fun `a scene-moving case becomes an exit of its screen`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "scene-move", genre = "RPG", createdAt = now, updatedAt = now))
        val user = users.save(testAppUser("mover", now))
        members.save(ProjectMemberEntity(projectId = project.id!!, appUserId = user.id!!, role = "OWNER", createdAt = now))
        val start = cases.save(TestCaseEntity(projectId = project.id!!, scene = "타이틀", step = "시작하기를 누른다.", expectedValue = "광장으로 간다."))
        val look = cases.save(TestCaseEntity(projectId = project.id!!, scene = "타이틀", step = "제목을 본다.", expectedValue = "제목이 보인다."))
        val fake = agent as FakeAgent
        fake.arrivals = mapOf(start.id!! to "광장")
        try {
            service.refresh(project.id!!)
        } finally {
            fake.arrivals = emptyMap()
        }

        val meta = ObjectMapper().readTree(cases.findById(start.id!!)!!.metadata.asString())
        assertThat(meta.path("arrives_at").asText()).isEqualTo("광장")
        assertThat(meta.path("contract").path("v").asInt()).isEqualTo(CaseContractService.CONTRACT_VERSION)
        val sent = testCases.getAuthoringCases(project.id!!, user.id!!).associateBy { it.id }
        assertThat(sent[start.id]!!.stateAfter).containsEntry("scene", "광장")
        assertThat(sent[look.id]!!.exits).containsExactly(SceneExit("광장", "TC ${start.id}: 시작하기를 누른다."))
    }

    /** 시작 값 질문의 답은 어느 케이스도 고치지 않고 프로젝트 시작 상태에 남는다. */
    @Test
    fun `a start value answer goes to the project's start state`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "start-value", genre = "RPG", createdAt = now, updatedAt = now))
        cases.save(case(project.id!!, "늑대를 공격한다."))
        service.refresh(project.id!!)

        service.applyAnswer(
            project.id!!,
            CaseContractService.ContractAnswerRef(0, "시간대", CaseContractService.START_OPERATOR, "?"),
            "${CaseContractService.START_VALUE}낮",
        )

        assertThat(service.startState(project.id!!)).containsEntry("시간대", "낮")
    }
}
