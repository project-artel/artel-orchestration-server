package kr.artel.orchestration

import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * TestScenario R2DBC 리포지토리의 저장/조회 왕복 및 Auditing(created/updated) 자동 채움을 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class TestScenarioRepositoryTest {

    @Autowired
    private lateinit var repository: TestScenarioRepository

    @Test
    fun testSaveAndFindByClientId() {
        val clientId = UUID.randomUUID().toString()

        val saved = repository.save(
            TestScenarioEntity(
                clientId = clientId,
                agentSessionId = "agent-sid-1",
                payload = """{"steps":[{"order":1,"description":"로그인"}]}"""
            )
        ).block()

        // Auditing이 id/created/updated를 채웠는지 검증
        assertThat(saved).isNotNull
        assertThat(saved!!.id).isNotNull
        assertThat(saved.createdAt).isNotNull
        assertThat(saved.updatedAt).isNotNull

        val found = repository.findByClientId(clientId).block()
        assertThat(found).isNotNull
        assertThat(found!!.agentSessionId).isEqualTo("agent-sid-1")
        assertThat(found.payload).contains("steps")
    }
}
