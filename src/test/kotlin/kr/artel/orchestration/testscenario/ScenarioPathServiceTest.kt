package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.service.ScenarioPathResult
import kr.artel.orchestration.testscenario.service.ScenarioPathService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 경로 조회의 세 답을 **정답을 아는 사례로** 고정한다(ARTEL-466).
 *
 * word-venture 실측에서 정답이 확정된 두 전이가 기준선이다.
 * - `position 0→1` — RightArrow 하나로 옮겨진다. 사이에 무언가가 **필요하다**
 * - `StagePosition 1→2` — 전투를 클리어해야 하는데 명세에 그 규칙이 없다. **모른다**
 *
 * 이 둘이 뒤집히면 저작이 다시 도달 불가 경로를 만들기 시작한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScenarioPathServiceTest {

    @Autowired private lateinit var service: ScenarioPathService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var contentMapRepository: ContentMapRepository
    @Autowired private lateinit var sceneRepository: SceneRepository
    @Autowired private lateinit var sceneEdgeRepository: SceneEdgeRepository
    @Autowired private lateinit var capabilityRepository: CapabilityRepository
    @Autowired private lateinit var effectRepository: CapabilityEffectRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val seq = AtomicLong(900_000)

    private var projectId: Long = 0
    private var userId: Long = 0
    private var mapSceneId: Long = 0
    private var battleSceneId: Long = 0

    @BeforeEach
    fun fixture(): Unit = runBlocking {
        val now = Instant.now()
        userId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "path-${seq.incrementAndGet()}",
                login = "path", displayName = "Path", avatarUrl = null, email = null,
            )
        )!!.userId.toLong()
        val project = projectRepository.save(
            ProjectEntity(name = "path-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = buildRepository.save(
            GameBuildEntity(
                projectId = projectId, version = "v-${seq.incrementAndGet()}",
                createdAt = now, updatedAt = now,
            )
        )
        val map = contentMapRepository.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 6, capture = "editor",
                evidenceDigest = "test-digest-${seq.incrementAndGet()}",
            )
        )
        mapSceneId = sceneRepository.save(
            SceneEntity(contentMapId = map.id!!, name = "Map_scene", walked = true)
        ).id!!
        battleSceneId = sceneRepository.save(
            SceneEntity(contentMapId = map.id!!, name = "TurnBattleScene", walked = true)
        ).id!!
    }

    // ---- 사례 -------------------------------------------------------------------------

    @Test
    fun `가드가 어긋나지 않으면 사이에 아무것도 필요 없다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.NOT_REQUIRED)
        assertThat(answer.capabilityIds).isEmpty()
    }

    /**
     * 실측 기준선 하나. RightArrow 가 `position` 을 옮긴다는 것이 명세에 있으므로 길이 있다.
     */
    @Test
    fun `값을 옮기는 기능이 있으면 그 기능을 답한다`(): Unit = runBlocking {
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(move, target = "MapMove.position", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(move)
        assertThat(answer.actions.single()).contains("RightArrow")
    }

    /**
     * 실측 기준선 둘. `StagePosition` 을 올리는 규칙이 명세에 없다 —
     * 실제로는 전투를 클리어해야 하는데 그 지식이 지도에 없다. **지어내지 않고 모른다고 답해야 한다.**
     */
    @Test
    fun `값을 바꿀 수단이 없으면 무엇이 막는지와 함께 모른다고 답한다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 2")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("StagePosition")
        assertThat(answer.note).contains("명세에 없다")
    }

    @Test
    fun `씬 간선이 있으면 그 조작을 답한다`(): Unit = runBlocking {
        val enter = capability(mapSceneId, interaction = "press", inputKey = "Return")
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = mapSceneId, toSceneName = "TurnBattleScene",
                toSceneId = battleSceneId, capabilityId = enter, source = "static",
            )
        )

        val a = case("Map_scene", "Map_scene 화면인 상태")
        val b = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(enter)
        assertThat(answer.actions.single()).contains("Map_scene → TurnBattleScene")
    }

    @Test
    fun `진입 경로를 모르는 씬으로는 씬 쌍을 들어 모른다고 답한다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태")
        val b = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("Map_scene→TurnBattleScene")
    }

    /**
     * 부등식을 읽지 않던 판은 실제 사전조건의 비교 중 58%를 "충돌 없음"으로 통과시켰다.
     * `>=` 가 어긋나는 것을 잡아야 한다.
     */
    @Test
    fun `부등식 가드가 어긋나는 것도 잡는다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position >= 3")

        val answer = service.findPath(projectId, userId, a, b)

        // 옮길 수단이 없으므로 모른다 — 그러나 **어긋난다는 것 자체는 잡혔다**.
        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("position")
    }

    @Test
    fun `씬 명세가 없는 프로젝트는 모른다고 답하고 예외를 던지지 않는다`(): Unit = runBlocking {
        val now = Instant.now()
        val bare = projectRepository.save(
            ProjectEntity(name = "bare-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        memberRepository.save(
            ProjectMemberEntity(projectId = bare.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val a = testCaseRepository.save(
            TestCaseEntity(projectId = bare.id!!, scene = "Map_scene", step = "a", expectedValue = "e")
        ).id!!
        val b = testCaseRepository.save(
            TestCaseEntity(projectId = bare.id!!, scene = "Map_scene", step = "b", expectedValue = "e")
        ).id!!

        val answer = service.findPath(bare.id!!, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("content-map")
    }

    @Test
    fun `다른 프로젝트의 케이스를 물으면 답하지 않는다`(): Unit = runBlocking {
        val mine = case("Map_scene", "Map_scene 화면인 상태")
        val answer = service.findPath(projectId, userId, mine, 999_999_999L)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).startsWith("case:")
    }

    // ---- 픽스처 ----------------------------------------------------------------------

    private suspend fun case(scene: String, precondition: String, stateAfter: String? = null): Long {
        val metadata = stateAfter?.let {
            Json.of(objectMapper.writeValueAsString(mapOf("source" to mapOf("state_after" to it))))
        } ?: Json.of("{}")
        return testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId, scene = scene, step = "step-${seq.incrementAndGet()}",
                precondition = precondition, expectedValue = "expected", metadata = metadata,
            )
        ).id!!
    }

    private suspend fun capability(
        sceneId: Long,
        interaction: String,
        inputKey: String? = null,
        label: String? = null,
    ): Long = capabilityRepository.save(
        CapabilityEntity(
            sceneId = sceneId, origin = "evidence", summary = "test capability",
            interaction = interaction, inputKey = inputKey, controlLabel = label,
            inputPhase = if (interaction == "press") "down" else null,
            status = "runnable",
        )
    ).id!!

    private suspend fun effect(capabilityId: Long, target: String, detail: String?) {
        effectRepository.save(
            CapabilityEffectEntity(
                capabilityId = capabilityId, category = "state", kind = "write",
                target = target, detail = detail, watchable = true,
            )
        )
    }
}
