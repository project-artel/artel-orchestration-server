package kr.artel.orchestration

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * TestScenario R2DBC 리포지토리의 저장/조회(JSONB payload) 및 Auditing(created/updated) 자동 채움,
 * 프로젝트당 여러 시나리오(Project 1:N) 저장을 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class TestScenarioRepositoryTest {

    @Autowired
    private lateinit var repository: TestScenarioRepository

    @Test
    fun testSaveAndFindByProjectId(): Unit = runBlocking {
        // 고유 projectId로 다른 테스트/실행과 격리
        val projectId = System.nanoTime()

        val saved = repository.save(
            TestScenarioEntity(
                projectId = projectId,
                payload = Json.of("""{"steps":[{"order":1,"description":"로그인"}]}""")
            )
        )

        // Auditing이 id/created/updated를 채웠는지 검증
        assertThat(saved).isNotNull
        assertThat(saved.id).isNotNull
        assertThat(saved.createdAt).isNotNull
        assertThat(saved.updatedAt).isNotNull

        val found = repository.findByProjectId(projectId).toList()
        assertThat(found).hasSize(1)
        assertThat(found[0].payload.asString()).contains("steps")
    }

    @Test
    fun testProjectCanHaveMultipleScenarios(): Unit = runBlocking {
        val projectId = System.nanoTime()

        repository.save(TestScenarioEntity(projectId = projectId, payload = Json.of("""{"n":1}""")))
        repository.save(TestScenarioEntity(projectId = projectId, payload = Json.of("""{"n":2}""")))

        val found = repository.findByProjectId(projectId).toList()
        assertThat(found).hasSize(2)
    }
}
