package kr.artel.orchestration.testscenario

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix.Link
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 짝 행렬을 **정답을 아는 지도 위에서** 고정한다(ARTEL-652).
 *
 * word-venture 실측에서 사흘 동안 세 번 틀렸던 그 모양을 그대로 세워 둔다. 지도가 안 바뀌었는데
 * 이 값이 바뀌면 코드가 바뀐 것이고, 그때 바뀐 방향이 맞는지는 사람이 봐야 한다.
 *
 * ```
 * 지도 진행도는 전투에서만 오른다(조작 아님). 타이틀 버튼은 0 으로 되돌린다.
 * 지도 위치는 방향키로 오르내린다.
 * ```
 */
@ActiveProfiles("test")
@SpringBootTest
class ScenarioFlowMatrixTest {

    @Autowired private lateinit var matrix: ScenarioFlowMatrix
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var contentMapRepository: ContentMapRepository
    @Autowired private lateinit var sceneRepository: SceneRepository
    @Autowired private lateinit var capabilityRepository: CapabilityRepository
    @Autowired private lateinit var effectRepository: CapabilityEffectRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val seq = AtomicLong(700_000)

    private var projectId: Long = 0
    private var userId: Long = 0
    private var contentMapId: Long = 0
    private var mapSceneId: Long = 0
    private var battleSceneId: Long = 0

    /** 지도 위 두 자리, 진행도로 갈리는 갈래 한 쌍, 그리고 아무도 안 쓰는 값 한 쌍. */
    private var atZero: Long = 0
    private var atOne: Long = 0
    private var notCleared: Long = 0
    private var cleared: Long = 0
    private var quiet: Long = 0
    private var quieter: Long = 0

    @BeforeEach
    fun fixture(): Unit = runBlocking {
        val now = Instant.now()
        userId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "matrix-${seq.incrementAndGet()}",
                login = "matrix", displayName = "Matrix", avatarUrl = null, email = null,
            )
        )!!.userId.toLong()
        val project = projectRepository.save(
            ProjectEntity(name = "matrix-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
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
                evidenceDigest = "matrix-digest-${seq.incrementAndGet()}",
            )
        )
        contentMapId = map.id!!
        mapSceneId = sceneRepository.save(
            SceneEntity(contentMapId = contentMapId, name = "Map_scene", walked = true)
        ).id!!
        battleSceneId = sceneRepository.save(
            SceneEntity(contentMapId = contentMapId, name = "TurnBattleScene", walked = true)
        ).id!!

        // 방향키는 지도 위치를 한 칸씩 옮긴다 — 시킬 수 있다.
        val right = capability(mapSceneId, "press", inputKey = "RightArrow")
        effect(right, "MapMove.position", "+1")
        val left = capability(mapSceneId, "press", inputKey = "LeftArrow")
        effect(left, "MapMove.position", "-1")
        // 진행도는 전투에서만 오른다 — 시킬 수 없다.
        val wave = capabilityRepository.save(
            CapabilityEntity(
                sceneId = battleSceneId, contentMapId = contentMapId, origin = "evidence",
                summary = "마지막 웨이브가 끝나면 오른다", interaction = "none", status = "not-a-step",
            )
        ).id!!
        effect(wave, "MapMove.StagePosition", "+1")
        // 타이틀 버튼은 되돌리기만 한다.
        val reset = capability(mapSceneId, "click", label = "새 게임")
        effect(reset, "MapMove.StagePosition", "0")

        atZero = case("Map_scene 화면인 상태 / MapMove.position == 0")
        atOne = case("Map_scene 화면인 상태 / MapMove.position == 1")
        notCleared = case("Map_scene 화면인 상태 / MapMove.StagePosition != 5")
        cleared = case("Map_scene 화면인 상태 / MapMove.StagePosition == 5")
        // 지도의 어떤 기능도 이 값을 쓰지 않는다 — 거쳐 갈 수도 없는 자리다.
        quiet = case("Map_scene 화면인 상태 / MapMove.knock == 1")
        quieter = case("Map_scene 화면인 상태 / MapMove.knock == 2")
    }

    @Test
    fun `값은 조작으로 · 거쳐서 · 아예 못 감 셋으로 갈린다`(): Unit = runBlocking {
        val found = matrix.of(projectId, userId, listOf(atZero, atOne, notCleared, cleared, quiet, quieter))

        // 위치는 방향키가 옮긴다.
        assertThat(found.between(atZero, atOne)?.link).isEqualTo(Link.BY_OPERATION)
        assertThat(found.between(atOne, atZero)?.link).isEqualTo(Link.BY_OPERATION)

        // 진행도를 5 로 만드는 조작은 없다 — 전투로만 오른다(런 216 이 여기서 틀렸다).
        // 그래도 게임을 하는 사람은 지나간다(ARTEL-655).
        val climb = found.between(notCleared, cleared)
        assertThat(climb?.link).isEqualTo(Link.BY_PLAY)
        assertThat(climb?.blockedBy).isEqualTo("StagePosition")

        // 반대 방향은 되돌리는 버튼이 있다.
        assertThat(found.between(cleared, notCleared)?.link).isEqualTo(Link.BY_OPERATION)

        // 아무 데서도 안 바뀌는 값은 거쳐 갈 수도 없다.
        assertThat(found.between(quiet, quieter)?.link).isEqualTo(Link.BLOCKED)
    }

    /** 방향이 다르면 답도 다르다 — 그래서 순서쌍을 다 푼다. */
    @Test
    fun `양쪽 방향을 모두 푼다`(): Unit = runBlocking {
        val ids = listOf(atZero, atOne, notCleared, cleared, quiet, quieter)

        val found = matrix.of(projectId, userId, ids)

        assertThat(found.testCaseIds).containsExactlyElementsOf(ids.sorted())
        // 자기 자신으로 가는 칸은 없다.
        assertThat(found.between(atZero, atZero)).isNull()
        // n × (n-1) 칸이 다 찬다.
        assertThat(
            Link.entries.sumOf { found.count(it) }
        ).isEqualTo(ids.size * (ids.size - 1))
    }

    /**
     * 흐름을 짤 때 묻는 것은 "이 자리 뒤에 무엇이 올 수 있나" 하나다.
     *
     * **거쳐 가는 자리는 뺄 것이 아니다**(ARTEL-655) — 사이가 GAP 이 될 뿐 흐름은 이어진다.
     * 정말 뺄 것은 아무 길도 없는 자리다.
     */
    @Test
    fun `아예 못 가는 자리만 다음에 올 수 있는 자리에서 빠진다`(): Unit = runBlocking {
        val found = matrix.of(projectId, userId, listOf(atZero, atOne, notCleared, cleared, quiet, quieter))

        assertThat(found.after(quiet)).doesNotContain(quieter)
        assertThat(found.after(notCleared)).contains(cleared)
        assertThat(found.after(cleared)).contains(notCleared)
    }

    private suspend fun case(precondition: String): Long = testCaseRepository.save(
        TestCaseEntity(
            projectId = projectId, scene = "Map_scene", step = "step-${seq.incrementAndGet()}",
            precondition = precondition, expectedValue = "expected",
        )
    ).id!!

    private suspend fun capability(
        sceneId: Long,
        interaction: String,
        inputKey: String? = null,
        label: String? = null,
    ): Long = capabilityRepository.save(
        CapabilityEntity(
            sceneId = sceneId, contentMapId = contentMapId, origin = "evidence", summary = "test",
            interaction = interaction, inputKey = inputKey, controlLabel = label,
            inputPhase = if (interaction == "press") "down" else null,
            status = "runnable",
        )
    ).id!!

    private suspend fun effect(capabilityId: Long, target: String, detail: String) {
        effectRepository.save(
            CapabilityEffectEntity(
                capabilityId = capabilityId, category = "state", kind = "write",
                target = target, detail = detail, watchable = true,
            )
        )
    }
}
