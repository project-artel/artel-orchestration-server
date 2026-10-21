package kr.artel.orchestration.testcase

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/**
 * 도는 생성을 취소하면 **이미 쓴 행도 남지 않는다**(ARTEL-1187).
 *
 * 생성은 `TransactionalOperator.executeAndAwait` 하나 안에서 돌고, 취소는 그 코루틴을 끊는다. 끊긴 트랜잭션이
 * 커밋되면(Spring 5.x 의 리액티브 트랜잭션은 취소를 커밋으로 다뤘다) 반쯤 쓴 TC 가 남는다. 생성 서비스와 같은 모양
 * (`supervisorScope` 안의 `Dispatchers.Default` `async` 자식이 트랜잭션을 열고, 바깥에서 그 자식을 cancel)으로 행을 쓴 뒤 끊어 본다.
 *
 * 통합 테스트의 취소 시험은 생성이 프로젝트 잠금 앞에서 멈춘 채 끊겨, 쓴 것이 없어서 이것을 보지 못한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class TestCaseGenerationRollbackTest {

    @Autowired private lateinit var transactionalOperator: TransactionalOperator
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var db: DatabaseClient

    @Test
    fun `쓴 뒤에 끊긴 트랜잭션은 롤백된다`(): Unit = runBlocking {
        val now = Instant.now()
        val projectId = projects.save(
            ProjectEntity(name = "rollback-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        val written = CompletableDeferred<Long>()

        supervisorScope {
            val run = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                transactionalOperator.executeAndAwait {
                    val saved = testCases.save(
                        TestCaseEntity(projectId = projectId, scene = "S", step = "쓰는 도중", expectedValue = "x", metadata = Json.of("{}"))
                    )
                    written.complete(saved.id!!)
                    awaitCancellation()
                }
            }
            run.start()
            val testCaseId = withTimeout(10_000) { written.await() }
            // 끊기 전에는 그 트랜잭션 안에 행이 있다(다른 커넥션에서는 아직 안 보인다).
            assertThat(testCaseId).isPositive()
            run.cancel()
            runCatching { run.await() }
        }

        val left = db.sql("SELECT count(*) AS n FROM test_case WHERE project_id = :projectId")
            .bind("projectId", projectId)
            .map { row, _ -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
        assertThat(left).isZero()
    }
}
