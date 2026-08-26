package kr.artel.orchestration.testcase

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

/**
 * 케이스가 **자기를 만든 지도 기능**을 가리킨다(ARTEL-553).
 *
 * 지금까지 저작은 케이스와 지도를 근거 **문자열**로 이었다. 그 꼬리가 메서드 단위라 기능 하나를
 * 가리키지 못했고(실측: `Map.MapMove.CharacterMove` 하나가 기능 14개 · 서로 다른 조작 6가지),
 * 좁히는 규칙을 따로 만들어야 했다. 키 하나면 그 전부가 사라진다.
 *
 * **이 칸이 비어 있는 것이 정상인 경우가 많다** — 손으로 만든 케이스, 엑셀로 적재된 케이스,
 * evidence 출신이 아닌 기능. 그래서 아래 테스트는 "채워진다"만큼 "비어도 된다"를 함께 못 박는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseCapabilityKeyIntegrationTest {

    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var db: DatabaseClient

    private suspend fun newProject(): Long {
        val now = Instant.now()
        return projects.save(
            ProjectEntity(name = "key-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
    }

    private suspend fun case(projectId: Long, key: String?): TestCaseEntity = testCases.save(
        TestCaseEntity(
            projectId = projectId,
            scene = "Map_scene",
            step = "step-${System.nanoTime()}",
            precondition = "Map_scene 화면인 상태",
            expectedValue = "expected",
            capabilityKey = key,
            metadata = Json.of("{}"),
        )
    )

    /**
     * 키는 지도의 `capability_key` 를 그대로 담는다. 실측 값의 모양이 이렇다 — 적재기가 앉힌 지도의
     * TC 창구 51행이 전부 이 칸을 들고 있다.
     */
    @Test
    fun `케이스가 기능의 안정 키를 담는다`(): Unit = runBlocking {
        val projectId = newProject()
        val key = "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()#21"

        val saved = case(projectId, key)

        assertThat(testCases.findById(saved.id!!)?.capabilityKey).isEqualTo(key)
    }

    /**
     * **키가 없는 케이스가 계속 있다.** 사람이 만든 것, 엑셀로 들어온 것, evidence 출신이 아닌 기능.
     * 이 칸을 필수로 만들면 그 전부가 저장되지 않는다.
     */
    @Test
    fun `키가 없어도 저장된다`(): Unit = runBlocking {
        val projectId = newProject()

        val saved = case(projectId, null)

        assertThat(testCases.findById(saved.id!!)?.capabilityKey).isNull()
    }

    /**
     * 같은 기능에서 케이스가 여럿 나온다 — 한 조작이 여러 결과를 내면 결과마다 확인할 것이 생긴다.
     * 그래서 이 칸은 유일하지 않다.
     */
    @Test
    fun `한 기능이 케이스 여럿을 가질 수 있다`(): Unit = runBlocking {
        val projectId = newProject()
        val key = "Assembly-CSharp|Scenes.TitleSceneManager|LoadStoryScene|System.Void()#3"

        case(projectId, key)
        case(projectId, key)

        val found = db.sql(
            "SELECT count(*) AS n FROM test_case WHERE project_id = $projectId AND capability_key = '$key'"
        ).fetch().one().awaitSingle()
        assertThat(found["n"] as Long).isEqualTo(2)
    }

    /**
     * 색인이 `(project_id, capability_key)` 인 이유: 키만으로는 어느 프로젝트의 것인지 모른다.
     * 다른 게임이 같은 어셈블리 이름을 쓰면 키가 겹칠 수 있다.
     */
    @Test
    fun `같은 키라도 프로젝트가 다르면 다른 케이스다`(): Unit = runBlocking {
        val key = "Assembly-CSharp|Core.SaveLoadController|SavePlayData|System.Void()#1"
        val mine = newProject()
        val theirs = newProject()

        case(mine, key)
        case(theirs, key)

        assertThat(testCases.findByProjectIdOrderByIdAsc(mine).toList().map { it.capabilityKey })
            .containsExactly(key)
    }
}
