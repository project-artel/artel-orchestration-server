package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * TestScenario R2DBC 리포지토리의 저장/조회 및 Auditing(created/updated) 자동 채움, 프로젝트당 여러
 * 시나리오(Project 1:N) 저장을 검증한다.
 *
 * 본문은 title/description 컬럼 + steps(JSONB)로 나뉜다(ARTEL-291, 이전엔 payload 한 덩어리).
 * 컬럼과 [ScenarioDraft] 사이의 왕복이 이 도메인의 유일한 직렬화 지점이라 여기서 함께 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class TestScenarioRepositoryTest {

    @Autowired
    private lateinit var repository: TestScenarioRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun testSaveAndFindByProjectId(): Unit = runBlocking {
        // 고유 projectId로 다른 테스트/실행과 격리
        val projectId = System.nanoTime()

        val saved = repository.save(
            TestScenarioEntity(projectId = projectId).withDraft(
                ScenarioDraft(
                    title = "로그인 여정",
                    description = "첫 진입부터 로비까지",
                    steps = listOf(ScenarioStep(action = "로그인 버튼 탭", caseId = 7L)),
                ),
                objectMapper,
            )
        )

        // Auditing이 id/created/updated를 채웠는지 검증
        assertThat(saved).isNotNull
        assertThat(saved.id).isNotNull
        assertThat(saved.createdAt).isNotNull
        assertThat(saved.updatedAt).isNotNull

        val found = repository.findByProjectId(projectId).toList()
        assertThat(found).hasSize(1)
        assertThat(found[0].title).isEqualTo("로그인 여정")
        assertThat(found[0].description).isEqualTo("첫 진입부터 로비까지")

        // steps는 JSONB 왕복이므로 구조가 보존되어야 한다.
        val draft = found[0].toDraft(objectMapper)
        assertThat(draft.steps).hasSize(1)
        assertThat(draft.steps[0].action).isEqualTo("로그인 버튼 탭")
        assertThat(draft.steps[0].caseId).isEqualTo(7L)
    }

    @Test
    fun testDefaultsFillEmptyBody(): Unit = runBlocking {
        // 생성 직후(빈 시나리오)엔 본문이 없다. NOT NULL 컬럼이 기본값으로 채워져야 INSERT가 선다.
        val saved = repository.save(TestScenarioEntity(projectId = System.nanoTime()))

        assertThat(saved.title).isEmpty()
        assertThat(saved.description).isEmpty()
        assertThat(saved.steps.asString()).isEqualTo("[]")
        assertThat(saved.toDraft(objectMapper).steps).isEmpty()
    }

    @Test
    fun testProjectCanHaveMultipleScenarios(): Unit = runBlocking {
        val projectId = System.nanoTime()

        repository.save(TestScenarioEntity(projectId = projectId, title = "첫째", steps = Json.of("[]")))
        repository.save(TestScenarioEntity(projectId = projectId, title = "둘째", steps = Json.of("[]")))

        val found = repository.findByProjectId(projectId).toList()
        assertThat(found).hasSize(2)
        assertThat(found.map { it.title }).containsExactlyInAnyOrder("첫째", "둘째")
    }
}
