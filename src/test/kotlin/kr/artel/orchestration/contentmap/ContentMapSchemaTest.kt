package kr.artel.orchestration.contentmap

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
import kr.artel.orchestration.contentmap.entity.CapabilityProofEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.ContentMapRoot
import kr.artel.orchestration.contentmap.entity.EffectCategory
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.RecordKind
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.SceneOrigin
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.ScreenTransitionEntity
import kr.artel.orchestration.contentmap.entity.TransitionKind
import kr.artel.orchestration.contentmap.entity.SpecGapReason
import kr.artel.orchestration.contentmap.entity.EdgeSource
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.InputPhase
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.Applicability
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.entity.SpecStatus
import kr.artel.orchestration.contentmap.entity.TriggerKind
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.CapabilityProofRepository
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
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
    @Autowired private lateinit var proofs: CapabilityProofRepository
    @Autowired private lateinit var edges: SceneEdgeRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var transitions: ScreenTransitionRepository
    @Autowired private lateinit var screenCapabilities: ScreenCapabilityRepository
    @Autowired private lateinit var db: DatabaseClient

    /** 스키마 제약을 직접 두드릴 때 쓴다 — 엔티티를 거치면 거절 시점이 가려진다. */
    private suspend fun exec(sql: String) {
        db.sql(sql).fetch().awaitRowsUpdated()
    }

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
     * **한 빌드에 지도는 하나다**(`uk_content_map_build`).
     *
     * capture 가 달라도 새 행이 되지 않는다. 갈라 둔 값을 아무도 쌍으로 안 읽는데 지도만 갈려,
     * 소비자가 언제나 세상의 절반만 보고 있었다(ARTEL-642). capture 는 사라지지 않고 씬으로
     * 내려가 "이 값을 어느 상태에서 읽었나"로 남는다.
     */
    @Test
    fun `한 빌드에 지도는 하나다`(): Unit = runBlocking {
        val build = newGameBuild()

        contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidenceDigest = "aaaa",
            )
        )

        assertThatThrownBy {
            runBlocking {
                contentMaps.save(
                    ContentMapEntity(
                        gameBuildId = build.id!!,
                        schemaVersion = 6,
                        capture = Capture.PLAYER.wire,
                        evidenceDigest = "aaaa",
                    )
                )
            }
        }.hasMessageContaining("uk_content_map_build")
    }

    /**
     * **근거 문서 없이도 지도가 선다.**
     *
     * QA 런이 근거 스캔보다 먼저 도는 빌드가 있고, 그때 지도를 못 세우면 관측한 것을 적을 자리가
     * 없다. `schema_version` · `evidence_digest` · `capture` 는 전부 문서가 말해 주는 것이라 그
     * 빌드에서는 아무도 말한 적이 없다 — 더미값을 넣으면 진짜 헤더와 같은 칸에 앉는다.
     */
    @Test
    fun `근거 문서 없이 관측만으로 지도가 선다`(): Unit = runBlocking {
        val build = newGameBuild()

        val observed = contentMaps.save(
            ContentMapEntity(gameBuildId = build.id!!, rootedBy = ContentMapRoot.OBSERVATION.wire)
        )

        val found = contentMaps.findByGameBuildId(build.id!!)
        assertThat(found?.id).isEqualTo(observed.id)
        assertThat(found?.schemaVersion).isNull()
        assertThat(found?.evidenceDigest).isNull()
        assertThat(found?.capture).isNull()
        assertThat(found?.rootedBy).isEqualTo(ContentMapRoot.OBSERVATION.wire)
    }

    /**
     * 씬이 자기 값을 **어느 상태에서 읽었는지**와 **어디서 왔는지**를 스스로 말한다.
     *
     * 둘 다 어휘 밖의 값을 CHECK 이 막는다. 막지 않으면 오타 하나가 표에 앉아, 그 씬을 읽는 쪽이
     * 조용히 분기를 놓친다.
     */
    @Test
    fun `씬이 capture 와 origin 을 든다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = scenes.save(
            SceneEntity(
                contentMapId = map.id!!,
                name = "TitleScene",
                capture = Capture.PLAYER.wire,
                origin = SceneOrigin.OBSERVED.wire,
            )
        )

        val found = scenes.findById(scene.id!!)!!
        assertThat(found.capture).isEqualTo(Capture.PLAYER.wire)
        assertThat(found.origin).isEqualTo(SceneOrigin.OBSERVED.wire)

        assertThatThrownBy {
            runBlocking { exec("INSERT INTO scene (content_map_id, name, capture) VALUES (${map.id}, 'Bad', 'editer')") }
        }.hasMessageContaining("ck_scene_capture")
        assertThatThrownBy {
            runBlocking { exec("INSERT INTO scene (content_map_id, name, origin) VALUES (${map.id}, 'Bad', 'inferred')") }
        }.hasMessageContaining("ck_scene_origin")
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "`Canvas/MapSceneButton` 클릭 → `TitleSceneManager.InitPlayerData()`",
                interaction = Interaction.CLICK.wire,
                controlSelector = "Canvas[2]/MapSceneButton[1]",
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        evidences.upsert(
            CapabilityEvidenceEntity(
                capabilityId = fromEvidence.id!!,
                entryId = "Assembly-CSharp|Scenes.TitleSceneManager|InitPlayerData|System.Void()",
                ownerType = "Scenes.TitleSceneManager",
                method = "InitPlayerData",
                methodId = "Scenes.TitleSceneManager::InitPlayerData",
                branchOffset = 12,
                recordKind = RecordKind.CANDIDATE.wire,
                triggerKind = TriggerKind.UNITY_EVENT.wire,
                analysisConfidence = AnalysisConfidence.DERIVED.wire,
                conditionTree = Json.of("""{"kind":"always"}"""),
                callPath = Json.of("""["Scenes.TitleSceneManager::InitPlayerData"]"""),
            )
        )

        val fromObservation = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "`Canvas/LogoImage` 를 3회 연속 클릭하면 `DebugPanel` 이 열린다",
                interaction = Interaction.CLICK.wire,
                controlSelector = "Canvas[2]/LogoImage[4]",
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                        contentMapId = scene.contentMapId,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "키 없는 press",
                        interaction = Interaction.PRESS.wire,
                        actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                    )
                )
            }
        }.hasMessageContaining("ck_capability_press_needs_key")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        contentMapId = scene.contentMapId,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "클릭인데 키가 붙었다",
                        interaction = Interaction.CLICK.wire,
                        inputKey = "Return",
                        actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`DebugCanvas/TurnEndButton` 클릭 → `TurnBattleSystem.TurnEndButton()`",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`WaveEndSensor()` 코루틴이 마지막 웨이브 뒤 `GameClearScene` 으로 보낸다",
                interaction = Interaction.NONE.wire,
                actionability = Actionability.NOT_A_STEP.wire,
            )
        )
        val merged = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "나중에 evidence 로도 확인되어 접힌 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`key:RightArrow` 를 누르면 `MapMove.position` 에 +1 을 쓴다",
                interaction = Interaction.PRESS.wire,
                inputKey = "RightArrow",
                inputPhase = InputPhase.DOWN.wire,
                actionability = Actionability.NEEDS_PROBE.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "자동 전이",
                interaction = Interaction.NONE.wire,
                actionability = Actionability.NOT_A_STEP.wire,
            )
        )

        // 세 칸이 다 찬 것 — 사유가 없어야 한다
        val runnable = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "`key:Return` 을 누르면 `TurnBattleScene` 으로 이동",
                interaction = Interaction.PRESS.wire,
                inputKey = "Return",
                inputPhase = InputPhase.DOWN.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "확인된 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "아직 안 눌러본 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        // 관측 출신은 분모에 들어가지 않는다 — 정적 분석이 알아낸 것이 아니다
        capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "관측으로 배운 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "눌러보고 배운 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                        methodId = "X::Y",
                        callPath = Json.of("""["X::Y"]"""),
                        recordKind = RecordKind.CANDIDATE.wire,
                        triggerKind = TriggerKind.UNITY_EVENT.wire,
                        analysisConfidence = AnalysisConfidence.EXACT.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "근거 행이 딸려오지 않은 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = summary,
                    interaction = Interaction.CLICK.wire,
                    actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                )
            )
            evidences.upsert(
                CapabilityEvidenceEntity(
                    capabilityId = capability.id!!,
                    entryId = "Assembly-CSharp|T|$summary|System.Void()",
                    ownerType = "T",
                    method = summary,
                    methodId = "T::$summary",
                    callPath = Json.of("""["T::$summary"]"""),
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
        val ambiguous = withGaps("ambiguous", null, AnalysisConfidence.AMBIGUOUS.wire)
        val clean = withGaps("clean", null, AnalysisConfidence.EXACT.wire)

        val gaps = contentMaps.findSpecGaps(map.id!!).toList().associate { it.capabilityId to it.reason }

        assertThat(gaps[subjectLost]).isEqualTo(SpecGapReason.GIVEN_SUBJECT_UNKNOWN.wire)
        assertThat(gaps[notComposed]).isEqualTo(SpecGapReason.GIVEN_INCOMPLETE.wire)
        assertThat(gaps[unread]).isEqualTo(SpecGapReason.GIVEN_UNREAD.wire)
        assertThat(gaps[ambiguous]).isEqualTo(SpecGapReason.GIVEN_INCOMPLETE.wire)
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "조건도 반쪽이고 결과도 내부 상태뿐",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.NEEDS_PROBE.wire,
            )
        )
        evidences.upsert(
            CapabilityEvidenceEntity(
                capabilityId = both.id!!,
                entryId = "Assembly-CSharp|T|both|System.Void()",
                ownerType = "T",
                method = "both",
                methodId = "T::both",
                callPath = Json.of("""["T::both"]"""),
                recordKind = RecordKind.CANDIDATE.wire,
                triggerKind = TriggerKind.UNITY_EVENT.wire,
                analysisConfidence = AnalysisConfidence.AMBIGUOUS.wire,
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
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "관측된 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
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

    /**
     * 같은 메서드 안의 서로 다른 지점이 서로 다른 기능으로 산다.
     *
     * `entry_id` 만으로 가르면 한 `Update()` 밑의 갈래 넷이 기능 하나로 눌린다. 실측에서
     * `CharacterMove` 가 9건이어야 할 자리에 3건이었다. 가르는 값은 `branch_offset` 하나뿐이라
     * 그것이 실제로 공존을 허용하는지 확인한다.
     */
    @Test
    fun `같은 메서드라도 갈라져 나온 위치가 다르면 별개 기능이다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "Map_scene")
        val entry = "Assembly-CSharp|Player.MapMove|Update|System.Void()"

        val offsets = listOf(24, 61, 98)
        val saved = offsets.map { offset ->
            val capability = capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = "`key:RightArrow` 누르면 `MapMove.position` 이 바뀐다 (offset $offset)",
                    interaction = Interaction.PRESS.wire,
                    inputKey = "RightArrow",
                    actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                    capabilityKey = "cap-$offset",
                )
            )
            evidences.upsert(
                CapabilityEvidenceEntity(
                    capabilityId = capability.id!!,
                    entryId = entry,
                    ownerType = "Player.MapMove",
                    method = "Update",
                    methodId = "Player.MapMove::Update",
                    branchOffset = offset,
                    recordKind = RecordKind.CANDIDATE.wire,
                    triggerKind = TriggerKind.LIFECYCLE.wire,
                    analysisConfidence = AnalysisConfidence.DERIVED.wire,
                    conditionTree = Json.of("""{"kind":"always"}"""),
                    callPath = Json.of("""["Player.MapMove::Update"]"""),
                )
            )
            capability
        }

        val stored = saved.mapNotNull { evidences.findById(it.id!!) }
        assertThat(stored).hasSize(3)
        assertThat(stored.map { it.branchOffset }).containsExactlyInAnyOrderElementsOf(offsets)
        // 주소가 메서드까지가 아니라 갈래까지 내려간다.
        assertThat(stored.map { it.entryId }.distinct()).hasSize(1)
    }

    /**
     * 안정 키는 content_map 안에서 하나뿐이다.
     *
     * 씬 단위로 잡으면 같은 타입이 두 씬에 놓였을 때 서로 다른 기능이 같은 키를 들 수 있고,
     * `scene_edge` 가 든 참조가 어느 쪽인지 못 가린다.
     */
    @Test
    fun `안정 키가 한 지도 안에서 중복되면 거절한다`(): Unit = runBlocking {
        val map = newContentMap()
        val title = newScene(map.id!!, "TitleScene")
        val battle = newScene(map.id!!, "TurnBattleScene")

        capabilities.save(
            CapabilityEntity(
                sceneId = title.id!!,
                contentMapId = title.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "먼저 자리를 잡은 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                capabilityKey = "cap-a1b2c3d4",
            )
        )

        // 씬이 달라도 같은 지도라 거절된다.
        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = battle.id!!,
                        contentMapId = battle.contentMapId,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "같은 키를 든 다른 기능",
                        interaction = Interaction.CLICK.wire,
                        actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                        capabilityKey = "cap-a1b2c3d4",
                    )
                )
            }
        }.hasMessageContaining("uk_capability_map_key")
    }

    /**
     * 키가 없는 기능은 여럿이어도 된다.
     *
     * observed · inferred · human 출신은 `entry_id` 가 없어 산식의 입력이 없다. 없는 것이 정상이라
     * UNIQUE 가 그것들을 서로 막으면 안 된다 — Postgres 가 NULL 을 서로 다르게 보는 것에 기댄다.
     */
    @Test
    fun `안정 키가 없는 기능은 한 지도에 여럿 산다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        repeat(2) { index ->
            capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.OBSERVED.wire,
                    verification = VerificationState.CONFIRMED.wire,
                    summary = "눌러보고 배운 기능 $index",
                    interaction = Interaction.CLICK.wire,
                    actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                )
            )
        }

        val observed = capabilities
            .findBySceneIdAndOriginOrderByIdAsc(scene.id!!, CapabilityOrigin.OBSERVED.wire)
            .toList()
        assertThat(observed).hasSize(2)
        assertThat(observed.map { it.capabilityKey }).containsOnlyNulls()
    }

    /**
     * "실행 가능 · 관측 불가"와 "적용 불가"가 서로 다르게 조회된다.
     *
     * 축을 나누기 전에는 둘 다 `needs-probe` 한 통이었다. 앞은 조작 스텝으로는 쓸 수 있고 판정
     * 근거로만 못 쓴다. 뒤는 아예 쓸 수 없다. 소비자가 그것을 가려야 한다.
     */
    @Test
    fun `관측 불가와 적용 불가가 갈린다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "Map_scene")

        suspend fun save(
            summary: String,
            observability: Observability,
            applicability: Applicability,
        ) = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = summary,
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = observability.wire,
                applicability = applicability.wire,
            )
        )

        val unobservable = save("실행은 되는데 결과를 못 본다", Observability.UNOBSERVABLE, Applicability.APPLIES)
        val notApplicable = save("이 빌드엔 없다", Observability.OBSERVABLE, Applicability.NOT_APPLICABLE)

        // status 만 보면 둘 다 "쓸 수 없음" 쪽이지만 같은 값이 아니다.
        assertThat(capabilities.findById(unobservable.id!!)!!.status)
            .isEqualTo(SpecStatus.NEEDS_PROBE.wire)
        assertThat(capabilities.findById(notApplicable.id!!)!!.status)
            .isEqualTo(SpecStatus.UNREACHABLE_PRECONDITION.wire)

        // 축으로 보면 왜 그런지가 갈린다.
        val byAxis = db
            .sql(
                """
                SELECT capability_id, actionability, observability, applicability
                FROM v_content_map_capability
                WHERE scene_id = :sceneId
                ORDER BY capability_id
                """
            )
            .bind("sceneId", scene.id!!)
            .map { row, _ ->
                Triple(
                    row.get("actionability", String::class.java),
                    row.get("observability", String::class.java),
                    row.get("applicability", String::class.java),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(byAxis).containsExactly(
            Triple("runnable", "unobservable", "applies"),
            Triple("runnable", "observable", "not-applicable"),
        )
    }

    /**
     * `status` 는 축에서 유도된 값이라 **따로 쓸 수 없다.**
     *
     * 쓸 수 있으면 언젠가 축과 어긋나고, 어긋난 뒤에는 어느 쪽이 참인지 아무도 모른다. 생성
     * 컬럼이라 DB 가 그 쓰기를 거절한다.
     */
    @Test
    fun `status 는 축과 어긋나게 쓸 수 없다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        assertThatThrownBy {
            runBlocking {
                exec(
                    """
                    INSERT INTO capability
                        (scene_id, content_map_id, origin, summary, interaction, actionability, status)
                    VALUES
                        (${scene.id}, ${scene.contentMapId}, 'evidence', '거짓 status', 'click',
                         'not-a-step', 'runnable')
                    """
                )
            }
        }.hasMessageContaining("status")

        // 축만 정하면 status 가 따라온다.
        val saved = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "축만 정한 기능",
                interaction = Interaction.NONE.wire,
                actionability = Actionability.NOT_A_STEP.wire,
            )
        )
        assertThat(capabilities.findById(saved.id!!)!!.status).isEqualTo(SpecStatus.NOT_A_STEP.wire)
    }

    /** 코드의 유도 규칙이 DB 생성 컬럼과 같은 답을 낸다. 두 벌이라 어긋나면 조용히 갈린다. */
    @Test
    fun `코드의 유도 규칙이 DB 와 같은 답을 낸다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        val cases = listOf(
            Triple(Actionability.RUNNABLE, Observability.OBSERVABLE, Applicability.APPLIES),
            Triple(Actionability.RUNNABLE, Observability.UNOBSERVABLE, Applicability.APPLIES),
            Triple(Actionability.RUNNABLE, Observability.OBSERVABLE, Applicability.NOT_APPLICABLE),
            Triple(Actionability.NEEDS_PROBE, Observability.OBSERVABLE, Applicability.APPLIES),
            Triple(Actionability.UNREACHABLE_PRECONDITION, Observability.OBSERVABLE, Applicability.APPLIES),
            Triple(Actionability.NOT_A_STEP, Observability.UNOBSERVABLE, Applicability.NOT_APPLICABLE),
            Triple(Actionability.RUNNABLE, Observability.UNKNOWN, Applicability.UNKNOWN),
        )

        cases.forEachIndexed { index, (actionability, observability, applicability) ->
            val saved = capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = "축 조합 $index",
                    interaction = Interaction.CLICK.wire,
                    actionability = actionability.wire,
                    observability = observability.wire,
                    applicability = applicability.wire,
                )
            )
            assertThat(capabilities.findById(saved.id!!)!!.status)
                .describedAs("$actionability / $observability / $applicability")
                .isEqualTo(SpecStatus.derive(actionability, observability, applicability).wire)
        }
    }

    /**
     * 확실성이 **어느 단계에서** 내려갔는지 읽힌다.
     *
     * 등급 하나로는 "이 결론이 틀렸다"까지만 말할 수 있다. 사슬이 있어야 호출을 잘못 따라간 것인지
     * 필드 쓰기를 잘못 읽은 것인지 갈리고, 그것이 적재기 규칙을 고칠 자리다.
     */
    @Test
    fun `사슬이 어느 단계에서 흐려졌는지 말한다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "GameClearScene")
        val capability = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "`Canvas/RewardButton` 클릭 → 보상 카드가 생성된다",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        val effect = effects.save(
            CapabilityEffectEntity(
                capabilityId = capability.id!!,
                category = EffectCategory.OBSERVABLE.wire,
                kind = "instantiate",
                target = "GameClearController.magicCard",
                resolution = AnalysisConfidence.AMBIGUOUS.wire,
            )
        )

        val chain = listOf(
            Triple(0, AnalysisConfidence.EXACT.wire, "binding-from-scene-calls"),
            Triple(1, AnalysisConfidence.DERIVED.wire, "callee-effect-composition"),
            Triple(2, AnalysisConfidence.AMBIGUOUS.wire, "prefab-field-resolution"),
        )
        chain.forEach { (seq, resolution, rule) ->
            proofs.save(
                CapabilityProofEntity(
                    capabilityId = capability.id!!,
                    effectId = effect.id!!,
                    seq = seq,
                    source = "GameClearController::ShowReward",
                    relation = "calls",
                    target = "GameClearController.magicCard",
                    resolution = resolution,
                    rule = rule,
                )
            )
        }

        val stored = proofs.findByEffectIdOrderBySeqAsc(effect.id!!).toList()
        assertThat(stored).hasSize(3)
        // 흐려진 단계와 그때 적용한 규칙이 함께 읽힌다.
        val blurred = stored.single { it.resolution == AnalysisConfidence.AMBIGUOUS.wire }
        assertThat(blurred.seq).isEqualTo(2)
        assertThat(blurred.rule).isEqualTo("prefab-field-resolution")
        // 효과의 등급은 사슬의 최솟값과 같다.
        assertThat(effects.findById(effect.id!!)!!.resolution)
            .isEqualTo(AnalysisConfidence.AMBIGUOUS.wire)
    }

    /**
     * `v_content_map_capability` 의 행 수가 사슬 때문에 흔들리지 않는다.
     *
     * 그 뷰는 효과조차 접지 않는다 — 행이 곱해지기 때문이다. 사슬은 효과마다 여러 단계라 더
     * 곱해지므로, 조인했다면 TC 생성기가 받는 행 수가 사슬 길이에 따라 달라졌을 것이다.
     */
    @Test
    fun `사슬을 쌓아도 TC 창구의 행 수는 그대로다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")
        val capability = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "`Canvas/MapSceneButton` 클릭",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )

        suspend fun rowsInWindow(): Int = db
            .sql("SELECT count(*) FROM v_content_map_capability WHERE capability_id = :id")
            .bind("id", capability.id!!)
            .map { row, _ -> (row.get(0) as Number).toInt() }
            .one()
            .awaitSingle()

        val before = rowsInWindow()

        repeat(5) { seq ->
            proofs.save(
                CapabilityProofEntity(
                    capabilityId = capability.id!!,
                    seq = seq,
                    source = "Scenes.TitleSceneManager::InitPlayerData",
                    relation = "calls",
                    resolution = AnalysisConfidence.DERIVED.wire,
                    rule = "callee-effect-composition",
                )
            )
        }

        assertThat(proofs.findByCapabilityIdOrderBySeqAsc(capability.id!!).toList()).hasSize(5)
        assertThat(rowsInWindow()).isEqualTo(before)
    }

    /**
     * 사슬 적재의 **크기 대가**를 잰다.
     *
     * 행이 기능당 수 배로 늘어나는 구조라, 얼마나 무거운지를 숫자로 알고 시작해야 한다. 골든 맵
     * 규모(기능 18 × 효과 2 × 단계 3 = 108 행)를 넣고 실제 점유를 읽는다.
     *
     * 상한이 헐거운 이유: 정확한 바이트는 PostgreSQL 판·정렬 패딩에 달렸다. 여기서 잡으려는 것은
     * 미세한 변동이 아니라 **한 자릿수가 바뀌는 회귀**다 — 사슬에 JSONB 를 하나 얹는 식의 변경.
     */
    @Test
    fun `사슬 적재의 크기 대가를 잰다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")
        val capability = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "크기를 재려고 세운 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )

        repeat(108) { seq ->
            proofs.save(
                CapabilityProofEntity(
                    capabilityId = capability.id!!,
                    seq = seq,
                    source = "Assembly-CSharp|Scenes.TitleSceneManager|InitPlayerData|System.Void()",
                    relation = "calls",
                    target = "Scenes.TitleSceneManager.saveLoadController.LoadPlayData",
                    resolution = AnalysisConfidence.DERIVED.wire,
                    rule = "callee-effect-composition",
                )
            )
        }

        // 이 클래스의 다른 테스트가 남긴 행이 섞이므로 이 기능 것만 센다. 페이지 단위인
        // pg_total_relation_size 는 이 규모에서 고정 오버헤드가 지배해 per-row 값이 뜻을 잃는다.
        // 튜플 실측은 pg_column_size 합으로 읽는다.
        val measured = db
            .sql(
                """
                SELECT count(*) AS rows, sum(pg_column_size(p.*)) AS bytes
                FROM capability_proof p WHERE p.capability_id = :id
                """
            )
            .bind("id", capability.id!!)
            .map { row, _ -> (row.get("rows") as Number).toLong() to (row.get("bytes") as Number).toLong() }
            .one()
            .awaitSingle()
        val (rows, bytes) = measured

        println("capability_proof rows=$rows tuple_bytes=$bytes bytes_per_row=${bytes / rows}")
        assertThat(rows).isEqualTo(108L)
        assertThat(bytes / rows).isLessThan(400L)
    }

    /** 한 사슬 안에서 순서가 겹치면 거절한다. 겹치면 "몇 번째 단계"가 뜻을 잃는다. */
    @Test
    fun `사슬의 순서는 겹치지 않는다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")
        val capability = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "사슬이 붙는 기능",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        fun step() = CapabilityProofEntity(
            capabilityId = capability.id!!,
            seq = 0,
            source = "T::M",
            relation = "calls",
            resolution = AnalysisConfidence.EXACT.wire,
            rule = "binding-from-scene-calls",
        )

        proofs.save(step())

        assertThatThrownBy { runBlocking { proofs.save(step()) } }
            .hasMessageContaining("uk_capability_proof_capability_seq")
    }

    /**
     * "대사가 끝날 때까지 아무 키를 누른다"가 **스텝으로** 적힌다.
     *
     * 이 자리가 없으면 반복이 사전조건으로 밀려나 "대사를 모두 넘긴 상태"가 되는데, 그것은 실행
     * 에이전트가 확인할 수 없는 전제다. `press` 가 키를 요구하므로 sentinel `any` 로 적는다.
     */
    @Test
    fun `끝날 때까지 반복하는 조작이 스텝으로 적힌다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "StoryScene")

        val saved = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "아무 키를 `StoryController` 의 대사가 끝날 때까지 반복해 누른다",
                interaction = Interaction.PRESS.wire,
                inputKey = Interaction.ANY_INPUT_KEY,
                inputPhase = InputPhase.DOWN.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                repeatUntilDone = true,
                // 확인할 수 없는 전제를 given 에 남기지 않는다. 종료 조건은 then 이 든다.
                givenText = null,
            )
        )

        val stored = capabilities.findById(saved.id!!)
        assertThat(stored).isNotNull
        assertThat(stored!!.repeatUntilDone).isTrue()
        assertThat(stored.inputKey).isEqualTo("any")
        assertThat(stored.givenText).isNull()
    }

    /** 기본값이 `false` 라, 반복을 모르는 적재 경로가 그대로 동작한다. */
    @Test
    fun `반복 표시는 기본이 꺼짐이다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        val saved = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "`Canvas/MapSceneButton` 클릭",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )

        assertThat(capabilities.findById(saved.id!!)!!.repeatUntilDone).isFalse()
    }

    /**
     * 조작이 없는 것은 반복일 수 없다.
     *
     * `interaction='none'` 은 타이머·로딩·코루틴이라 누를 것이 없다. 그것을 "끝날 때까지
     * 반복한다"고 적으면 TC 가 지시할 수 없는 것을 지시하게 된다.
     */
    @Test
    fun `조작 없는 기능은 반복으로 적을 수 없다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        contentMapId = scene.contentMapId,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "코루틴이 웨이브를 끝낸다",
                        interaction = Interaction.NONE.wire,
                        actionability = Actionability.NOT_A_STEP.wire,
                        repeatUntilDone = true,
                    )
                )
            }
        }.hasMessageContaining("ck_capability_repeat_needs_interaction")
    }

    /**
     * `capability.content_map_id` 는 씬의 것과 어긋날 수 없다.
     *
     * 씬을 통해 이미 알 수 있는 값을 한 벌 더 든 것이라, 막지 않으면 두 벌이 갈라진다. 갈라지는
     * 순간 안정 키의 유일성 범위가 조용히 다른 맵으로 새어 나간다 — UNIQUE 는 이 컬럼만 보므로
     * 거짓 값이면 거짓 범위를 지킨다.
     */
    @Test
    fun `기능이 든 지도가 씬의 지도와 다르면 거절한다`(): Unit = runBlocking {
        val map = newContentMap()
        val other = newContentMap(capture = Capture.PLAYER.wire)
        val scene = newScene(map.id!!, "TitleScene")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        // 씬은 map 소속인데 다른 지도를 든다.
                        contentMapId = other.id!!,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "엉뚱한 지도를 든 기능",
                        interaction = Interaction.CLICK.wire,
                        actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                    )
                )
            }
        }.hasMessageContaining("fk_capability_scene_map")
    }

    /**
     * 되짚기의 두 축은 비어도 되지만, 비었다면 **왜** 비었는지가 남아야 한다.
     *
     * 실측에서 `method_id` 는 18건 중 2건, `call_path` 는 1건만 실려 있었고 그 사실이 아무 데도
     * 안 남아 "적재기가 못 채운 것"과 "근거에 원래 없는 것"이 구분되지 않았다.
     */
    @Test
    fun `되짚기 축이 비면 사유를 요구한다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TitleScene")

        suspend fun evidenceFor(
            summary: String,
            methodId: String?,
            callPath: String,
            gaps: String,
        ) {
            val capability = capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = summary,
                    interaction = Interaction.CLICK.wire,
                    actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                )
            )
            evidences.upsert(
                CapabilityEvidenceEntity(
                    capabilityId = capability.id!!,
                    entryId = "Assembly-CSharp|T|$summary|System.Void()",
                    ownerType = "T",
                    method = summary,
                    methodId = methodId,
                    recordKind = RecordKind.CANDIDATE.wire,
                    triggerKind = TriggerKind.UNITY_EVENT.wire,
                    analysisConfidence = AnalysisConfidence.AMBIGUOUS.wire,
                    conditionTree = Json.of("""{"kind":"always"}"""),
                    callPath = Json.of(callPath),
                    gaps = Json.of(gaps),
                )
            )
        }

        // 사유를 남기면 비어도 통과한다.
        evidenceFor(
            summary = "gap-declared",
            methodId = null,
            callPath = "[]",
            gaps = """["${EvidenceGap.METHOD_ID_MISSING.wire}","${EvidenceGap.CALL_PATH_MISSING.wire}"]""",
        )

        // 사유 없이 비면 거절된다.
        assertThatThrownBy {
            runBlocking {
                evidenceFor(summary = "silent-method", methodId = null, callPath = """["T::x"]""", gaps = "[]")
            }
        }.hasMessageContaining("ck_capability_evidence_method_id_or_gap")

        assertThatThrownBy {
            runBlocking {
                evidenceFor(summary = "silent-path", methodId = "T::y", callPath = "[]", gaps = "[]")
            }
        }.hasMessageContaining("ck_capability_evidence_call_path_or_gap")
    }

    /**
     * 프리팹 위에만 사는 타입은 배선으로 찾을 수 없고, **무엇이 만드는가**가 유일한 주소다.
     *
     * 그 주소를 담을 자리가 없으면 씬만 남고 입구가 사라진다 — 실측(`wv-editor-latest.json`)에서
     * `unplaced` 10타입 · evidence 111건이 이 자리에 걸린다.
     */
    @Test
    fun `스폰으로 씬에 붙은 기능은 만드는 쪽을 적는다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        val spawned = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = scene.contentMapId,
                origin = CapabilityOrigin.EVIDENCE.wire,
                summary = "`Cards.Card` 가 뒤집힌다",
                // 조작이 아니다. 만들어지는 쪽을 누를 방법이 없다.
                interaction = Interaction.NONE.wire,
                actionability = Actionability.NOT_A_STEP.wire,
                spawnedByField = "Cards.CardManager.cardPrefab",
                spawnedByScenePath = "CardSystem/CardManager",
            )
        )

        val stored = capabilities.findById(spawned.id!!)!!
        assertThat(stored.spawnedByField).isEqualTo("Cards.CardManager.cardPrefab")
        assertThat(stored.spawnedByScenePath).isEqualTo("CardSystem/CardManager")
        // 조작으로 세어지지 않는다. 축은 CHECK 가 강제하고, 그 결과 TC 창구에서 빠진다.
        assertThat(stored.status).isEqualTo(SpecStatus.NOT_A_STEP.wire)
    }

    /**
     * 이 마이그레이션의 본체다.
     *
     * 만드는 쪽의 주소를 조준 대상으로 내주면 TC 는 **카드 매니저를 눌러 카드가 뒤집혔다**고
     * 적는다. 조작이 아닌 것이 조작인 척하는 이 한 줄이 근거 없는 명세 중 가장 비싸다.
     */
    @Test
    fun `스폰 출처를 조준 대상으로 옮겨 담을 수 없다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        suspend fun save(
            summary: String,
            controlPath: String? = null,
            controlSelector: String? = null,
            controlLabel: String? = null,
            interaction: String = Interaction.NONE.wire,
            actionability: String = Actionability.NOT_A_STEP.wire,
        ) {
            capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = summary,
                    interaction = interaction,
                    controlPath = controlPath,
                    controlSelector = controlSelector,
                    controlLabel = controlLabel,
                    actionability = actionability,
                    spawnedByField = "Cards.CardManager.cardPrefab",
                )
            )
        }

        // 쥔 오브젝트의 사람용 경로도,
        assertThatThrownBy {
            runBlocking { save(summary = "경로를 든 스폰 행", controlPath = "CardSystem/CardManager") }
        }.hasMessageContaining("ck_capability_spawn_has_no_control")

        // 조준용 selector 도,
        assertThatThrownBy {
            runBlocking { save(summary = "selector 를 든 스폰 행", controlSelector = "CardSystem[1]/CardManager[1]") }
        }.hasMessageContaining("ck_capability_spawn_has_no_control")

        // 프롬프트 재료가 되는 글자도 막는다. 경로만 비우고 이름을 남기면 "만드는 쪽을 눌러라"가
        // 문장으로 되살아난다.
        assertThatThrownBy {
            runBlocking { save(summary = "이름을 든 스폰 행", controlLabel = "카드 매니저") }
        }.hasMessageContaining("ck_capability_spawn_has_no_control")

        // 누를 자리 없이 남은 조작 종류도 막는다.
        assertThatThrownBy {
            runBlocking { save(summary = "클릭이라 적힌 스폰 행", interaction = Interaction.CLICK.wire) }
        }.hasMessageContaining("ck_capability_spawn_has_no_control")

        // 축을 명시하지 않으면 기본값 runnable 이 status 를 needs-probe 로 유도해 TC 창구를
        // 통과한다. 조작 없는 행이 거기 나타나는 것이 이 CHECK 가 막는 마지막 구멍이다.
        assertThatThrownBy {
            runBlocking { save(summary = "축을 안 정한 스폰 행", actionability = Actionability.RUNNABLE.wire) }
        }.hasMessageContaining("ck_capability_spawn_has_no_control")
    }

    /**
     * 스폰 출처는 근거 문서만 말할 수 있다.
     *
     * QA 가 관측으로 배운 기능에 "무엇이 만든다"를 적으면 추측이 근거와 같은 칸에 들어가고,
     * 축이 둘이라는 전제가 반대쪽에서 무너진다.
     */
    @Test
    fun `관측으로 배운 기능에는 스폰 출처를 적을 수 없다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        contentMapId = scene.contentMapId,
                        origin = CapabilityOrigin.OBSERVED.wire,
                        summary = "관측이 본 카드",
                        interaction = Interaction.NONE.wire,
                        actionability = Actionability.NOT_A_STEP.wire,
                        spawnedByField = "Cards.CardManager.cardPrefab",
                    )
                )
            }
        }.hasMessageContaining("ck_capability_spawn_needs_evidence")
    }

    /**
     * 경로는 필드를 설명하는 값이라 혼자 서지 못한다.
     *
     * 경로만 남으면 "이 오브젝트가 만든다"까지만 있고 무엇으로 만드는지가 없어, 재적재 때 같은
     * 사실인지 확인할 수 없다.
     */
    @Test
    fun `만드는 쪽 필드 없이 경로만 적을 수 없다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        assertThatThrownBy {
            runBlocking {
                capabilities.save(
                    CapabilityEntity(
                        sceneId = scene.id!!,
                        contentMapId = scene.contentMapId,
                        origin = CapabilityOrigin.EVIDENCE.wire,
                        summary = "경로만 든 스폰 행",
                        interaction = Interaction.NONE.wire,
                        actionability = Actionability.NOT_A_STEP.wire,
                        spawnedByScenePath = "CardSystem/CardManager",
                    )
                )
            }
        }.hasMessageContaining("ck_capability_spawn_path_needs_field")
    }

    /**
     * 스폰 행은 조작이 **없어야 맞는** 행이라 when-missing 을 채우지 않는다.
     *
     * v_spec_gap 은 "다음에 무엇을 고칠까"에 답하는 표다. 실측 111건이 when-missing 으로 쏟아지면
     * 조작을 못 가진 다른 행들이 그 밑에 묻힌다. 빼고 나면 스폰 행도 나머지 분기를 그대로
     * 지나간다 — 조건이 흐리면 given-incomplete, 관측 가능 효과가 없으면 then-missing 이다.
     * 아래 사례는 조건이 derived 라 뒤쪽까지 간다.
     */
    @Test
    fun `스폰 행은 조작 없음을 구멍으로 세지 않는다`(): Unit = runBlocking {
        val map = newContentMap()
        val scene = newScene(map.id!!, "TurnBattleScene")

        suspend fun save(summary: String, spawnedByField: String?): Long {
            val capability = capabilities.save(
                CapabilityEntity(
                    sceneId = scene.id!!,
                    contentMapId = scene.contentMapId,
                    origin = CapabilityOrigin.EVIDENCE.wire,
                    summary = summary,
                    interaction = Interaction.NONE.wire,
                    actionability = Actionability.NOT_A_STEP.wire,
                    spawnedByField = spawnedByField,
                )
            )
            evidences.upsert(
                CapabilityEvidenceEntity(
                    capabilityId = capability.id!!,
                    entryId = "Assembly-CSharp|Cards.Card|$summary|System.Void()",
                    ownerType = "Cards.Card",
                    method = summary,
                    methodId = "Cards.Card::$summary",
                    recordKind = RecordKind.CANDIDATE.wire,
                    triggerKind = TriggerKind.LIFECYCLE.wire,
                    analysisConfidence = AnalysisConfidence.DERIVED.wire,
                    conditionTree = Json.of("""{"kind":"always"}"""),
                    callPath = Json.of("""["Cards.Card::$summary"]"""),
                )
            )
            return capability.id!!
        }

        val spawned = save(summary = "spawned", spawnedByField = "Cards.CardManager.cardPrefab")
        // 스폰이 아닌 조작 없는 행. 조작을 잃은 것인지 원래 자동인 것(타이머·코루틴)인지는
        // 이 칸이 아직 가르지 못한다 — when-missing 에 남은 몫이다.
        val withoutInteraction = save(summary = "lost", spawnedByField = null)

        // reason 이 NULL 이면 구멍이 아니라는 뜻이라 빈 문자열로 받는다.
        suspend fun reasonOf(capabilityId: Long): String = db
            .sql("SELECT coalesce(reason, '') AS reason FROM v_spec_gap WHERE capability_id = :id")
            .bind("id", capabilityId)
            .map { row, _ -> row.get("reason", String::class.java) ?: "" }
            .one()
            .awaitSingle()

        assertThat(reasonOf(spawned)).isEqualTo(SpecGapReason.THEN_MISSING.wire)
        assertThat(reasonOf(withoutInteraction)).isEqualTo(SpecGapReason.WHEN_MISSING.wire)
    }
}
