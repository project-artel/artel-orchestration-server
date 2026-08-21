package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
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
import kr.artel.orchestration.testscenario.service.ScenarioCaseFactService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 케이스를 지도에 비춰 보는 답(ARTEL-466).
 *
 * 근거 키의 모양은 **실제 데이터에서 그대로 가져왔다** — 케이스는
 * `Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79` 로, 지도는 같은 것을
 * `Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()` 로 부른다. 네임스페이스 접두와 오프셋이
 * 다른 이 어긋남이 예전에 "두 쪽이 안 붙는다"고 본 이유였다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScenarioCaseFactServiceTest {

    @Autowired private lateinit var service: ScenarioCaseFactService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var contentMapRepository: ContentMapRepository
    @Autowired private lateinit var sceneRepository: SceneRepository
    @Autowired private lateinit var capabilityRepository: CapabilityRepository
    @Autowired private lateinit var template: R2dbcEntityTemplate
    @Autowired private lateinit var effectRepository: CapabilityEffectRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val seq = AtomicLong(500_000)

    private var projectId: Long = 0
    private var userId: Long = 0
    private var contentMapId: Long = 0
    private var mapSceneId: Long = 0

    @BeforeEach
    fun fixture(): Unit = runBlocking {
        val now = Instant.now()
        userId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "facts-${seq.incrementAndGet()}",
                login = "facts", displayName = "Facts", avatarUrl = null, email = null,
            )
        )!!.userId.toLong()
        projectId = projectRepository.save(
            ProjectEntity(name = "facts-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!.id!!
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
                evidenceDigest = "facts-${seq.incrementAndGet()}",
            )
        )
        contentMapId = map.id!!
        mapSceneId = sceneRepository.save(
            SceneEntity(contentMapId = map.id!!, name = "Map_scene", walked = true)
        ).id!!
    }

    @Test
    fun `근거 키로 그 케이스가 가리키는 조작을 찾는다`(): Unit = runBlocking {
        val move = capability("RightArrow")
        evidence(move, "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()")
        val case = case(
            evidence = "Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79",
        )

        val facts = service.explain(projectId, userId, case)

        assertThat(facts.operations).hasSize(1)
        assertThat(facts.operations.single().capabilityId).isEqualTo(move)
        assertThat(facts.operations.single().input).isEqualTo("key:RightArrow")
        // 정확히 이것인지, 아마 이 중 하나인지를 구분해서 낸다.
        assertThat(facts.operations.single().matchedBy).isEqualTo("evidence")
    }

    @Test
    fun `근거 키가 여러 개면 모두 본다`(): Unit = runBlocking {
        val move = capability("RightArrow")
        evidence(move, "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()")
        val enter = capability("Return")
        evidence(enter, "Assembly-CSharp|Map.MapMove|SelectStage|System.Void(System.Int32)")
        val case = case(
            evidence = "Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79 / " +
                "Assembly-CSharp|WordVenture.Map.MapMove|SelectStage|System.Void(System.Int32)@12",
        )

        val facts = service.explain(projectId, userId, case)

        assertThat(facts.operations.map { it.capabilityId }).containsExactlyInAnyOrder(move, enter)
        // 몇 번의 조작인지가 이 답의 핵심이다 — 스텝 하나로 뭉개지 않게 하려는 것.
        assertThat(facts.note).contains("2건")
    }

    @Test
    fun `값으로 닿은 것은 근거 키로 닿은 것과 구분한다`(): Unit = runBlocking {
        val move = capability("RightArrow")
        effect(move, target = "MapMove.position", detail = "+1")
        val case = case(supportingState = "`MapMove.position` write `+1`")

        val facts = service.explain(projectId, userId, case)

        assertThat(facts.operations.single().matchedBy).isEqualTo("effect")
    }

    @Test
    fun `지도가 모르는 케이스는 조작 없이 그렇다고 답한다`(): Unit = runBlocking {
        // 빈 배열이 정상적인 답이다. 지어낸 조작 이름을 주는 것보다 낫고, 그 목록이 곧 지도의 구멍이다.
        val case = case(evidence = "Assembly-CSharp|WordVenture.Enemies.Player|TakeHit|System.Void()@3")

        val facts = service.explain(projectId, userId, case)

        assertThat(facts.operations).isEmpty()
        assertThat(facts.observable).isNull()
        assertThat(facts.note).contains("지어내지 말 것")
    }

    @Test
    fun `사전조건과 실행 뒤 상태를 파싱해서 함께 낸다`(): Unit = runBlocking {
        val case = case(
            precondition = "Map_scene 화면인 상태 / (MapMove.StagePosition >= 1 그리고 MapMove.position == 0)",
            stateAfter = "MapMove.position=1",
        )

        val facts = service.explain(projectId, userId, case)

        assertThat(facts.scene).isEqualTo("Map_scene")
        assertThat(facts.stateBefore.map { it.variable }).containsExactly("StagePosition", "position")
        assertThat(facts.stateAfter).containsEntry("position", "1")
    }

    @Test
    fun `되읽을 수 없는 기대결과인지 알려준다`(): Unit = runBlocking {
        val move = capability("RightArrow")
        evidence(move, "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()")
        effect(move, target = "MapMove.position", detail = "+1", watchable = false)
        val case = case(evidence = "Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79")

        val facts = service.explain(projectId, userId, case)

        // 되읽을 수 없는 값을 확인하라고 적으면 실행이 판정 불가로 떨어진다.
        assertThat(facts.observable).isFalse()
    }

    @Test
    fun `다른 프로젝트의 케이스는 설명하지 않는다`(): Unit = runBlocking {
        val facts = service.explain(projectId, userId, 999_999_999L)

        assertThat(facts.operations).isEmpty()
        assertThat(facts.note).contains("없는 케이스")
    }

    // ---- 픽스처 ----------------------------------------------------------------------

    private suspend fun case(
        precondition: String = "Map_scene 화면인 상태",
        evidence: String? = null,
        supportingState: String? = null,
        stateAfter: String? = null,
    ): Long {
        val source = buildMap {
            evidence?.let { put("evidence", it) }
            supportingState?.let { put("supporting_state", it) }
            stateAfter?.let { put("state_after", it) }
        }
        return testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId, scene = "Map_scene", step = "step-${seq.incrementAndGet()}",
                precondition = precondition, expectedValue = "expected",
                metadata = Json.of(objectMapper.writeValueAsString(mapOf("source" to source))),
            )
        ).id!!
    }

    private suspend fun capability(inputKey: String): Long = capabilityRepository.save(
        CapabilityEntity(
            sceneId = mapSceneId, contentMapId = contentMapId, origin = "evidence",
            summary = "$inputKey 를 누른다",
            interaction = "press", inputKey = inputKey, inputPhase = "down", status = "runnable",
        )
    ).id!!

    private suspend fun evidence(capabilityId: Long, methodId: String) {
        // capability_evidence 는 PK 가 capability_id 라 save() 가 UPDATE 로 나간다. 실제 적재
        // 경로(ARTEL-441)는 아직 없어 이 자리에서만 걸리므로, 테스트가 INSERT 를 직접 낸다.
        template.insert(
            CapabilityEvidenceEntity(
                capabilityId = capabilityId,
                entryId = "Assembly-CSharp|Map.MapMove|Update|System.Void()",
                ownerType = "Map.MapMove", method = methodId.split("|")[2], methodId = methodId,
                recordKind = "candidate", triggerKind = "lifecycle", analysisConfidence = "derived",
                conditionTree = Json.of("{}"),
                // 호출 경로가 비면 gap 에 그 사실을 적어야 한다(ck_capability_evidence_call_path_or_gap).
                // 실제 근거는 경로를 들고 오므로 여기서도 들려 보낸다.
                callPath = Json.of("[\"System.Void Map.MapMove::Update()\"]"),
            )
        ).awaitSingle()
    }

    private suspend fun effect(
        capabilityId: Long,
        target: String,
        detail: String?,
        watchable: Boolean = true,
    ) {
        effectRepository.save(
            CapabilityEffectEntity(
                capabilityId = capabilityId, category = "state", kind = "write",
                target = target, detail = detail, watchable = watchable,
            )
        )
    }
}
