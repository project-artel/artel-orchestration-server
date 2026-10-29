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
        override suspend fun extract(request: CaseContractRequest): CaseContractResponse {
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
                    )
                },
            )
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
}
