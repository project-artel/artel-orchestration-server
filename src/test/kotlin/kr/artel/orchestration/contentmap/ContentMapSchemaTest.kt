package kr.artel.orchestration.contentmap

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.EffectCategory
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.RecordKind
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.ScreenTransitionEntity
import kr.artel.orchestration.contentmap.entity.TransitionKind
import kr.artel.orchestration.contentmap.entity.SpecGapReason
import kr.artel.orchestration.contentmap.entity.EdgeSource
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.InputPhase
import kr.artel.orchestration.contentmap.entity.SpecStatus
import kr.artel.orchestration.contentmap.entity.TriggerKind
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.CapabilityEvidenceRepository
import kr.artel.orchestration.contentmap.repository.upsert
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenCapabilityRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.repository.ScreenTransitionRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * content_map 스키마(V40)의 저장·조회와, 스키마가 지켜야 하는 규칙을 검증한다.
 *
 * 이 도메인은 컬럼이 많지만 **틀리면 조용히 거짓 명세를 만드는 자리가 몇 개로 좁다.** 그 자리들만
 * 골라 검증한다:
 *
 * 1. `origin` 과 `verification` 이 서로 다른 축이라는 것 — evidence 출신과 관측 출신이 한 씬에
 *    나란히 살고, 스캔 재적재가 후자를 지우지 않는다
 * 2. `interaction='press'` 와 `input_key` 의 쌍 — DB CHECK 가 강제한다
 * 3. `v_content_map_capability` 가 `not-a-step` 과 접힌 행을 거른다는 것 — TC 생성기가 실행할 수
 *    없는 것을 받으면 안 된다
 * 4. `v_spec_gap` 이 사유를 분류한다는 것 — 이 분포가 다음에 무엇을 고칠지 정한다
 */
@ActiveProfiles("test")
@SpringBootTest
class ContentMapSchemaTest {

    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var evidences: CapabilityEvidenceRepository
    @Autowired private lateinit var effects: CapabilityEffectRepository
    @Autowired private lateinit var edges: SceneEdgeRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var transitions: ScreenTransitionRepository
    @Autowired private lateinit var screenCapabilities: ScreenCapabilityRepository

    /** 게임 빌드는 프로젝트에 FK 로 매달려 있어 프로젝트부터 만든다. */
    private suspend fun newGameBuild(): GameBuildEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(
                name = "content-map-${System.nanoTime()}",
                genre = "ACTION",
                createdAt = now,
                updatedAt = now,
            )
        )
        return gameBuilds.save(
            GameBuildEntity(
                projectId = project.id!!,
                version = "v${System.nanoTime()}",
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private suspend fun newContentMap(capture: String = Capture.EDITOR.wire): ContentMapEntity {
        val build = newGameBuild()
        return contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = capture,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3",
                backend = "mono",
                development = true,
                sdkVersion = "0.1.0",
            )
        )
    }

    private suspend fun newScene(contentMapId: Long, name: String): SceneEntity =
        scenes.save(SceneEntity(contentMapId = contentMapId, name = name, walked = true))

    /**
     * 같은 빌드라도 `editor` 스캔과 `player` 스캔은 별개 행이다.
     *
     * 두 capture 에서 같은 필드가 다른 뜻이기 때문이다 — 적의 `label` 이 authored `20` 인가 남은
     * 체력 `20` 인가가 갈린다. 한 행에 섞으면 그 구분이 사라진다.
     */
    @Test
    fun `capture 가 다르면 같은 빌드라도 별개 지도다`(): Unit = runBlocking {
        val build = newGameBuild()

        val editor = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidenceDigest = "aaaa",
            )
        )
        val player = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "aaaa",
            )
        )

        assertThat(editor.id).isNotEqualTo(player.id)
        assertThat(contentMaps.findByGameBuildIdAndCapture(build.id!!, Capture.EDITOR.wire)?.id)
            .isEqualTo(editor.id)
        assertThat(contentMaps.findByGameBuildIdOrderByIdDesc(build.id!!).toList()).hasSize(2)
    }

    /**
     * 문서가 하는 약속(`evidence_promises`)이 왕복한다.
     *
     * 이름을 원문(`capabilities`)에서 바꾼 것은 기능 테이블과 충돌하기 때문이다 — 한쪽은 게임의
     * 기능이고 한쪽은 문서의 계약이다.
     */
    @Test
    fun `문서의 약속 목록이 왕복한다`(): Unit = runBlocking {
        val saved = newContentMap()
        val found = contentMaps.findById(saved.id!!)

        assertThat(found).isNotNull
        assertThat(found!!.evidencePromises.asString()).contains("selector-v1")
        assertThat(found.createdAt).isNotNull
        assertThat(found.updatedAt).isNotNull
    }

    /**
     * evidence 출신과 관측 출신이 한 씬에 나란히 산다.
     *
     * 관측 출신은 `capability_evidence` 행이 **없는 것이 정직한 상태다.** 그것을 NOT NULL 로 막아
     * 더미값을 넣게 하면 두 종류가 구분 불가능해진다.
     */
    @Test
    fun `관측으로 배운 기능은 근거 행 없이 산다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        val fromEvidence = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "`Canvas/MapSceneButton` 클릭 → `TitleSceneManager.InitPlayerData()`",
                interaction = Interaction.CLICK.wire,
                controlSelector = "Canvas[2]/MapSceneButton[1]",
                status = SpecStatus.RUNNABLE.wire,
            )
        )
        evidences.upsert(
            CapabilityEvidenceEntity(
                capabilityId = fromEvidence.id!!,
                entryId = "Assembly-CSharp|Scenes.TitleSceneManager|InitPlayerData|System.Void()",
                ownerType = "Scenes.TitleSceneManager",
                method = "InitPlayerData",
                recordKind = RecordKind.CANDIDATE.wire,
                triggerKind = TriggerKind.UNITY_EVENT.wire,
                analysisConfidence = AnalysisConfidence.DERIVED.wire,
                conditionTree = Json.of("""{"kind":"always"}"""),
            )
        )

        val fromObservation = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "`Canvas/LogoImage` 를 3회 연속 클릭하면 `DebugPanel` 이 열린다",
                interaction = Interaction.CLICK.wire,
                controlSelector = "Canvas[2]/LogoImage[4]",
                status = SpecStatus.RUNNABLE.wire,
            )
        )

        assertThat(evidences.findById(fromEvidence.id!!)).isNotNull
        // 근거 행이 없는 것이 정상이다. 이 null 이 "IL 근거가 없다"를 그대로 말한다.
        assertThat(evidences.findById(fromObservation.id!!)).isNull()

        val byOrigin = capabilities
            .findBySceneIdAndOriginOrderByIdAsc(scene.id!!, CapabilityOrigin.OBSERVED.wire)
            .toList()
        assertThat(byOrigin).hasSize(1)
        assertThat(byOrigin[0].id).isEqualTo(fromObservation.id)
    }

    /**
     * `interaction='press'` 는 `input_key` 를 요구하고, 그 반대도 성립한다.
     *
     * 키 없는 키 입력은 실행기가 받을 수 없고, 클릭에 붙은 키는 읽는 쪽을 헷갈리게 한다.
     */
    @Test
    fun `키 입력은 키 이름을 요구한다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "Map_scene")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "키 없는 press",
                        interaction = Interaction.PRESS.wire,
                        status = SpecStatus.RUNNABLE.wire,
                    )
                )
            }
        }.hasMessageContaining("ck_capability_press_needs_key")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "클릭인데 키가 붙었다",
                        interaction = Interaction.CLICK.wire,
                        inputKey = "Return",
                        status = SpecStatus.RUNNABLE.wire,
                    )
                )
            }
        }.hasMessageContaining("ck_capability_press_needs_key")
    }

    /**
     * TC 생성기가 읽는 뷰는 실행할 수 없는 것을 내주지 않는다.
     *
     * `not-a-step` 은 조작이 없어 단독 명세가 될 수 없고(코루틴·타이머), 접힌 행은 다른 기능으로
     * 대체된 것이다. 둘 다 TC 로 만들면 agent 가 수행할 수 없는 스텝을 받는다.
     */
    @Test
    fun `뷰가 실행 불가능한 기능을 거른다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        // 관측 출신으로 만든다. evidence 출신은 근거 행을 반드시 가져야 하므로, 근거 없이
        // 만들면 이 테스트가 스스로 불변식을 어긴다.
        val runnable = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`DebugCanvas/TurnEndButton` 클릭 → `TurnBattleSystem.TurnEndButton()`",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`WaveEndSensor()` 코루틴이 마지막 웨이브 뒤 `GameClearScene` 으로 보낸다",
                interaction = Interaction.NONE.wire,
                status = SpecStatus.NOT_A_STEP.wire,
            )
        )
        val merged = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "나중에 evidence 로도 확인되어 접힌 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )
        capabilities.save(merged.copy(mergedInto = runnable.id))

        val rows = contentMaps.findCapabilityRows(map.id!!).toList()

        assertThat(rows.map { it.capabilityId }).containsExactly(runnable.id)
        assertThat(rows[0].sceneName).isEqualTo("TurnBattleScene")
        // 관측 출신이라 IL 근거가 없고, 그래서 근거 컬럼이 null 이다. 그것이 정직한 상태다.
        assertThat(rows[0].entryId).isNull()
    }

    /**
     * 명세가 못 된 이유를 분류한다.
     *
     * 이것은 QA 결함이 아니라 **개발 우선순위 신호**다. `then-missing` 이 많으면 수집기(SDK)를
     * 고칠 차례다.
     */
    @Test
    fun `명세가 못 된 이유를 사유별로 낸다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "Map_scene")

        // 효과가 내부 상태뿐 — 화면에서 무엇이 달라지는지 근거에 없다
        val needsProbe = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`key:RightArrow` 를 누르면 `MapMove.position` 에 +1 을 쓴다",
                interaction = Interaction.PRESS.wire,
                inputKey = "RightArrow",
                inputPhase = InputPhase.DOWN.wire,
                status = SpecStatus.NEEDS_PROBE.wire,
            )
        )
        effects.save(
            CapabilityEffectEntity(
                capabilityId = needsProbe.id!!,
                category = EffectCategory.STATE.wire,
                kind = "write",
                target = "MapMove.position",
                detail = "+1",
                watchable = true,
            )
        )

        // 조작이 없다 — 코루틴이 스스로 하는 일
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "자동 전이",
                interaction = Interaction.NONE.wire,
                status = SpecStatus.NOT_A_STEP.wire,
            )
        )

        // 세 칸이 다 찬 것 — 사유가 없어야 한다
        val runnable = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`key:Return` 을 누르면 `TurnBattleScene` 으로 이동",
                interaction = Interaction.PRESS.wire,
                inputKey = "Return",
                inputPhase = InputPhase.DOWN.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )
        effects.save(
            CapabilityEffectEntity(
                capabilityId = runnable.id!!,
                category = EffectCategory.OBSERVABLE.wire,
                kind = "scene",
                target = "TurnBattleScene",
                detail = "TurnBattleScene",
                watchable = true,
            )
        )

        val gaps = contentMaps.findSpecGaps(map.id!!).toList().associate { it.capabilityId to it.reason }

        assertThat(gaps[needsProbe.id]).isEqualTo(SpecGapReason.THEN_MISSING.wire)
        assertThat(gaps.values).contains(SpecGapReason.WHEN_MISSING.wire)
        // 세 칸이 다 찬 기능은 사유 목록에 없다
        assertThat(gaps).doesNotContainKey(runnable.id)
    }

    /**
     * 씬 전이는 정적 후보로 출발하고, 아직 못 가본 간선이 커버리지 구멍이다.
     *
     * 관측에서 파생시키면 이 구멍이 보이지 않는다 — 안 가본 전이는 관측에 없기 때문이다.
     * `to_scene_name` 을 이름으로 두는 것도 같은 이유다. 아직 순회하지 못한 씬으로 가는 전이가 있다.
     */
    @Test
    fun `못 가본 씬 전이가 커버리지 구멍으로 남는다`(): Unit = runBlocking {
        val map = newContentMap()
        val title = newScene(map.id!!, "TitleScene")

        edges.save(
            SceneEdgeEntity(
                fromSceneId = title.id!!,
                toSceneName = "Map_scene",
                givenText = "`SaveLoadController.LoadPlayData() != -1`",
                source = EdgeSource.STATIC.wire,
            )
        )
        edges.save(
            SceneEdgeEntity(
                fromSceneId = title.id!!,
                toSceneName = "StoryScene",
                givenText = "`SaveLoadController.LoadPlayData() == -1`",
                source = EdgeSource.STATIC.wire,
                verifiedAt = Instant.now(),
                observedCount = 2,
            )
        )

        val all = edges.findByFromSceneIdOrderByIdAsc(title.id!!).toList()
        val unverified = edges.findByFromSceneIdAndVerifiedAtIsNullOrderByIdAsc(title.id!!).toList()

        assertThat(all).hasSize(2)
        assertThat(unverified).hasSize(1)
        assertThat(unverified[0].toSceneName).isEqualTo("Map_scene")
        // 아직 순회하지 않은 씬이라 id 는 비어 있고 이름만 있다
        assertThat(unverified[0].toSceneId).isNull()
    }

    /** 커버리지 지표의 분모와 분자. 분모는 정적 분석 성능, 분자는 agent 성능이다. */
    @Test
    fun `검증 비율을 센다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.EVIDENCE.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "확인된 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "아직 안 눌러본 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )
        // 관측 출신은 분모에 들어가지 않는다 — 정적 분석이 알아낸 것이 아니다
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "관측으로 배운 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )

        val count = capabilities.countEvidenceVerification(map.id!!)

        assertThat(count).isNotNull
        assertThat(count!!.total).isEqualTo(2)
        assertThat(count.verified).isEqualTo(1)
    }

    /**
     * 관측 출신 기능에는 IL 근거를 붙일 수 없다. **복합 FK 가 막는다.**
     *
     * 이것을 막지 않으면 근거 없이 배운 기능이 `entry_id` 를 갖게 되고, 읽는 쪽이 그것을 IL 이
     * 증명한 사실로 읽는다 — "축이 둘"이라는 전제가 반대쪽에서 무너지는 자리다.
     */
    @Test
    fun `관측 출신 기능에는 근거를 붙일 수 없다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        val observed = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "눌러보고 배운 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )

        assertThatThrownBy {
            runBlocking {
                evidences.upsert(
                    CapabilityEvidenceEntity(
                        capabilityId = observed.id!!,
                        entryId = "Assembly-CSharp|X|Y|System.Void()",
                        ownerType = "X",
                        method = "Y",
                        recordKind = RecordKind.CANDIDATE.wire,
                        triggerKind = TriggerKind.UNITY_EVENT.wire,
                        analysisConfidence = AnalysisConfidence.VERIFIED.wire,
                        conditionTree = Json.of("""{"kind":"always"}"""),
                    )
                )
            }
        }.hasMessageContaining("fk_capability_evidence_origin")
    }

    /**
     * 반대 방향(evidence 출신인데 근거가 없음)은 선언으로 막을 수 없다 — 행이 두 INSERT 로
     * 나뉘기 때문이다. 대신 `v_spec_gap` 이 `evidence-missing` 으로 **세어서 드러낸다.**
     *
     * 이 사유가 세어지면 고칠 곳은 SDK 가 아니라 우리 적재기다. `then-missing` 으로 뭉뚱그리면
     * 수집기를 고치러 가서 헛짚는다.
     */
    @Test
    fun `근거를 잃은 기능은 적재기 결함으로 드러난다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        val orphan = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "근거 행이 딸려오지 않은 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )

        val gaps = contentMaps.findSpecGaps(map.id!!).toList().associate { it.capabilityId to it.reason }

        assertThat(gaps[orphan.id]).isEqualTo(SpecGapReason.EVIDENCE_MISSING.wire)
    }

    /**
     * 근거의 `gaps` 토큰이 사유로 번역된다.
     *
     * 입력 어휘([EvidenceGap])와 출력 어휘([SpecGapReason])가 다르다는 것이 이 테스트의 요점이다.
     * 적재기가 출력 이름을 넣으면 분기가 영영 안 걸리고 뷰는 조용히 다른 사유를 낸다.
     */
    @Test
    fun `근거의 gaps 토큰이 사유로 번역된다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "Map_scene")

        suspend fun withGaps(summary: String, gapToken: String?, confidence: String): Long {
            val capability = capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = summary,
                    interaction = Interaction.CLICK.wire,
                    status = SpecStatus.RUNNABLE.wire,
                )
            )
            evidences.upsert(
                CapabilityEvidenceEntity(
                    capabilityId = capability.id!!,
                    entryId = "Assembly-CSharp|T|$summary|System.Void()",
                    ownerType = "T",
                    method = summary,
                    recordKind = RecordKind.CANDIDATE.wire,
                    triggerKind = TriggerKind.UNITY_EVENT.wire,
                    analysisConfidence = confidence,
                    conditionTree = Json.of("""{"kind":"always"}"""),
                    gaps = Json.of(gapToken?.let { """["$it"]""" } ?: "[]"),
                )
            )
            // 관측 가능한 효과를 붙여 then-* 분기가 가리지 않게 한다
            effects.save(
                CapabilityEffectEntity(
                    capabilityId = capability.id!!,
                    category = EffectCategory.OBSERVABLE.wire,
                    kind = "scene",
                    target = "SomeScene",
                    detail = "SomeScene",
                    watchable = true,
                )
            )
            return capability.id!!
        }

        val subjectLost = withGaps("subjectLost", EvidenceGap.SUBJECT_NULL.wire, AnalysisConfidence.DERIVED.wire)
        val notComposed = withGaps(
            "notComposed",
            EvidenceGap.CALLEE_CONDITION_NOT_COMPOSED.wire,
            AnalysisConfidence.DERIVED.wire,
        )
        val unread = withGaps("unread", EvidenceGap.UNREAD_CONDITION.wire, AnalysisConfidence.DERIVED.wire)
        val partial = withGaps("partial", null, AnalysisConfidence.PARTIAL.wire)
        val clean = withGaps("clean", null, AnalysisConfidence.VERIFIED.wire)

        val gaps = contentMaps.findSpecGaps(map.id!!).toList().associate { it.capabilityId to it.reason }

        assertThat(gaps[subjectLost]).isEqualTo(SpecGapReason.GIVEN_SUBJECT_UNKNOWN.wire)
        assertThat(gaps[notComposed]).isEqualTo(SpecGapReason.GIVEN_INCOMPLETE.wire)
        assertThat(gaps[unread]).isEqualTo(SpecGapReason.GIVEN_UNREAD.wire)
        assertThat(gaps[partial]).isEqualTo(SpecGapReason.GIVEN_INCOMPLETE.wire)
        assertThat(gaps).doesNotContainKey(clean)
    }

    /**
     * 사유가 여럿 성립하면 먼저 걸리는 것 하나만 나온다.
     *
     * 순서가 바뀌면 이 테스트가 깨진다 — 그러라고 있는 테스트다. given-* 를 앞에 둔 대가로
     * then-missing 이 과소계상된다는 것이 여기 박제된다.
     */
    @Test
    fun `사유가 경합하면 given 이 then 을 가린다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "Map_scene")

        // 조건도 불완전하고 관측 가능한 효과도 없다 — 두 사유가 동시에 성립한다
        val both = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "조건도 반쪽이고 결과도 내부 상태뿐",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.NEEDS_PROBE.wire,
            )
        )
        evidences.upsert(
            CapabilityEvidenceEntity(
                capabilityId = both.id!!,
                entryId = "Assembly-CSharp|T|both|System.Void()",
                ownerType = "T",
                method = "both",
                recordKind = RecordKind.CANDIDATE.wire,
                triggerKind = TriggerKind.UNITY_EVENT.wire,
                analysisConfidence = AnalysisConfidence.PARTIAL.wire,
                conditionTree = Json.of("""{"kind":"unknown"}"""),
            )
        )
        effects.save(
            CapabilityEffectEntity(
                capabilityId = both.id!!,
                category = EffectCategory.STATE.wire,
                kind = "write",
                target = "T.counter",
                detail = "+1",
            )
        )

        val gaps = contentMaps.findSpecGaps(map.id!!).toList().associate { it.capabilityId to it.reason }

        assertThat(gaps[both.id]).isEqualTo(SpecGapReason.GIVEN_INCOMPLETE.wire)
    }

    /**
     * 자동 전이(기능 없이 일어나는 것)가 중복 누적되지 않는다.
     *
     * Postgres 는 UNIQUE 에서 NULL 을 서로 다른 값으로 보므로 `(from, to, capability_id)` 만으로는
     * `capability_id IS NULL` 인 행을 막지 못한다. 그런데 자동 전이야말로 반복 관측되는 것이라,
     * 막지 않으면 `observed_count` 가 중복 행에 쪼개져 누적된다.
     */
    @Test
    fun `자동 전이는 화면 쌍마다 하나다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")
        val from = screens.save(
            ScreenEntity(sceneId = scene.id!!, discriminator = Json.of("""[{"a":1}]"""))
        )
        val to = screens.save(
            ScreenEntity(sceneId = scene.id!!, discriminator = Json.of("""[{"a":2}]"""))
        )

        transitions.save(
            ScreenTransitionEntity(
                fromScreenId = from.id!!,
                toScreenId = to.id!!,
                kind = TransitionKind.AUTO.wire,
                crossesScene = false,
            )
        )

        assertThatThrownBy {
            runBlocking {
                transitions.save(
                    ScreenTransitionEntity(
                        fromScreenId = from.id!!,
                        toScreenId = to.id!!,
                        kind = TransitionKind.AUTO.wire,
                        crossesScene = false,
                    )
                )
            }
        }.hasMessageContaining("uk_screen_transition_auto")
    }

    /**
     * 화면이 기능을 제공하더라는 관측이 누적되고, 눌렀는데 아무것도 안 변한 횟수가 따로 센다.
     *
     * 손으로 쓴 upsert SQL 이라 실행해 보지 않으면 조용히 틀린다.
     */
    @Test
    fun `화면의 기능 관측이 누적된다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")
        val screen = screens.save(
            ScreenEntity(sceneId = scene.id!!, discriminator = Json.of("""[{"active":true}]"""))
        )
        val capability = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "관측된 기능",
                interaction = Interaction.CLICK.wire,
                status = SpecStatus.RUNNABLE.wire,
            )
        )

        screenCapabilities.observe(screen.id!!, capability.id!!, firedIncrement = 1)
        screenCapabilities.observe(screen.id!!, capability.id!!, firedIncrement = 0)
        screenCapabilities.observe(screen.id!!, capability.id!!, firedIncrement = 1)

        val rows = screenCapabilities.findByScreenId(screen.id!!).toList()

        assertThat(rows).hasSize(1)
        assertThat(rows[0].observedCount).isEqualTo(3)
        // 세 번 눌러 두 번만 무언가 변했다. 그 차이가 결함 신호다.
        assertThat(rows[0].firedCount).isEqualTo(2)
    }
}
