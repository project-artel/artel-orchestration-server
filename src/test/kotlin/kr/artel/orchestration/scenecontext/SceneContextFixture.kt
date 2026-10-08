package kr.artel.orchestration.scenecontext

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.SceneOrigin
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

/**
 * ARTEL-611 테스트가 공유하는 픽스처.
 *
 * 두 테스트 클래스가 같은 세계(프로젝트 → 빌드 → 지도 → 씬 → 기능, 그리고 지식 → 앵커)를 세운다.
 * 한쪽에만 두면 다른 쪽이 자기 사본을 만들고, 그 순간 두 사본이 서로 다른 씬 이름·다른 상태 축을
 * 쓰기 시작해 어느 쪽이 진짜 계약인지 알 수 없어진다.
 */
class SceneContextFixture(
    private val projects: ProjectRepository,
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val scenes: SceneRepository,
    private val capabilities: CapabilityRepository,
    private val knowledge: KnowledgeRepository,
    private val anchors: KnowledgeAnchorRepository,
    private val db: DatabaseClient,
) {

    suspend fun newProject(): Long {
        val now = Instant.now()
        return projects.save(
            ProjectEntity(name = "scene-context-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
    }

    suspend fun newBuild(projectId: Long): Long {
        val now = Instant.now()
        return gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        ).id!!
    }

    suspend fun newContentMap(gameBuildId: Long, capture: Capture = Capture.PLAYER): Long =
        contentMaps.save(
            ContentMapEntity(
                gameBuildId = gameBuildId,
                schemaVersion = 6,
                capture = capture.wire,
                evidencePromises = Json.of("""["build-info-v1"]"""),
                evidenceDigest = "digest-${System.nanoTime()}",
            )
        ).id!!

    suspend fun newScene(
        contentMapId: Long,
        name: String,
        summary: String? = null,
        origin: SceneOrigin = SceneOrigin.EVIDENCE,
    ): Long =
        scenes.save(
            SceneEntity(
                contentMapId = contentMapId,
                name = name,
                summary = summary,
                walked = true,
                origin = origin.wire,
            )
        ).id!!

    /**
     * 기능 한 줄. 축 셋을 인자로 여는 이유는 `status` 가 그 셋에서 유도되는 생성 컬럼이라(V45),
     * `not-a-step` 을 만들려면 `actionability` 를 직접 줘야 하기 때문이다.
     */
    @Suppress("LongParameterList")
    suspend fun newCapability(
        contentMapId: Long,
        sceneId: Long,
        summary: String,
        interaction: String = "click",
        actionability: String = "runnable",
        observability: String = "observable",
        applicability: String = "applies",
        verification: String = "unverified",
        controlSelector: String? = null,
        controlPath: String? = null,
        controlLabel: String? = null,
        givenText: String? = null,
        inputKey: String? = null,
        /**
         * 근거의 진입점. 같은 값을 준 기능들이 **사람이 세는 한 기능**이다(ARTEL-805).
         *
         * null 이면 `capability_evidence` 행을 안 만든다 — 근거 출신이 아닌 기능이 그 모양이고,
         * 그때는 접히지 않는 것이 맞다.
         */
        entryId: String? = null,
    ): Long =
        capabilities.save(
            CapabilityEntity(
                sceneId = sceneId,
                contentMapId = contentMapId,
                capabilityKey = "key-${System.nanoTime()}",
                origin = "evidence",
                verification = verification,
                summary = summary,
                givenText = givenText,
                controlSelector = controlSelector,
                controlPath = controlPath,
                controlLabel = controlLabel,
                interaction = interaction,
                inputKey = inputKey,
                actionability = actionability,
                observability = observability,
                applicability = applicability,
            )
        ).id!!.also { capabilityId -> entryId?.let { writeEvidence(capabilityId, it) } }

    /**
     * `capability_evidence` 한 행. 접기가 읽는 것은 `entry_id` 하나다.
     *
     * 나머지는 이 표가 요구하는 최소치다. `NOT NULL` 여덟 칸에 더해 CHECK 둘이 **값이 없으면
     * 그 사실을 `gaps` 에 적으라**고 요구한다(`ck_capability_evidence_method_id_or_gap`,
     * `..._call_path_or_gap`). 그 규율을 픽스처에서도 지킨다 — 빈 값을 몰래 넣는 대신 없다고
     * 적는 편이 이 표가 하려는 말과 같다.
     */
    private suspend fun writeEvidence(capabilityId: Long, entryId: String) {
        db.sql(
            """
            INSERT INTO capability_evidence
                (capability_id, entry_id, owner_type, method, record_kind,
                 trigger_kind, analysis_confidence, condition_tree, method_id, gaps)
            VALUES (:id, :entryId, 'Fixture.Owner', 'Method', 'candidate', 'unity-event',
                    'exact', '{}'::jsonb, :entryId,
                    '["call-path-missing"]'::jsonb)
            """
        ).bind("id", capabilityId).bind("entryId", entryId).then().awaitFirstOrNull()
    }

    /**
     * `screen` 한 행. 같은 `scene` 안에서 `discriminator` 가 유일해야 하므로(`uk_screen_discriminator`)
     * 호출마다 다른 selector 를 넣는다.
     */
    suspend fun newScreen(sceneId: Long, name: String? = null): Long =
        db.sql(
            """
            INSERT INTO screen (scene_id, name, discriminator)
            VALUES (:sceneId, :name, :discriminator::jsonb)
            RETURNING id
            """
        )
            .bind("sceneId", sceneId)
            .let { if (name == null) it.bindNull("name", String::class.java) else it.bind("name", name) }
            .bind("discriminator", """[{"selector":"Canvas[0]/Screen${System.nanoTime()}","active":true}]""")
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one().block()!!

    /**
     * 등록된 `macro` 한 행과 그것이 달린 `screen` 관계. `MacroDefinitionService` 를 지나지 않는다 —
     * 그 경로는 QA 런과 agent 세션을 요구해 이 조회와 상관없는 픽스처를 여섯 개 더 세워야 한다.
     *
     * `definition_json` 은 실제로 저장되는 모양(`entry.parameters[].declared_type`)이다.
     * [parameters] 는 (이름, 타입) 쌍이고, [bodyComment] 가 있으면 본문 첫 줄에 `#` 주석으로 적는다.
     */
    suspend fun newMacro(
        contentMapId: Long,
        name: String,
        parameters: List<Pair<String, String>> = emptyList(),
        bodyComment: String? = null,
        screenIds: List<Long> = emptyList(),
    ): Long {
        val signature = parameters.joinToString(", ") { (parameterName, type) -> "$parameterName: $type" }
        val source = buildString {
            appendLine("def $name($signature) -> None:")
            bodyComment?.let { appendLine("    # $it") }
            append("    click(selector(\"Canvas/Button\"))")
        }
        val declaredParameters = parameters.joinToString(",", "[", "]") { (parameterName, type) ->
            """{"name":"$parameterName","default":null,"declared_type":"$type"}"""
        }
        val definitionJson =
            """{"name":"$name","entry":{"name":"$name","parameters":$declaredParameters,"statements":[]},"helpers":[],"source":"unused"}"""
        val parameterNames = parameters.joinToString(",", "[", "]") { (parameterName, _) -> "\"$parameterName\"" }
        val macroId = db.sql(
            """
            INSERT INTO macro (content_map_id, name, source, definition_json, parameter_names)
            VALUES (:contentMapId, :name, :source, :definitionJson::jsonb, :parameterNames::jsonb)
            RETURNING id
            """
        )
            .bind("contentMapId", contentMapId)
            .bind("name", name)
            .bind("source", source)
            .bind("definitionJson", definitionJson)
            .bind("parameterNames", parameterNames)
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one().block()!!
        screenIds.forEach { screenId ->
            db.sql("INSERT INTO screen_macro (screen_id, macro_id) VALUES (:screenId, :macroId)")
                .bind("screenId", screenId).bind("macroId", macroId).then().awaitFirstOrNull()
        }
        return macroId
    }

    /** 지식 한 항목 + 앵커. [sceneName] 이 null 이면 앵커 없는 지식(= 게임 전체의 사실)이다. */
    suspend fun newKnowledge(
        projectId: Long,
        summary: String,
        description: String,
        sceneName: String? = null,
        scopeId: Long? = null,
        shadowsId: Long? = null,
    ): Long {
        val saved = knowledge.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "QA",
                tag = "RULE",
                summary = summary,
                description = description,
                scopeId = scopeId,
                shadowsId = shadowsId,
            )
        )
        if (sceneName != null) {
            anchors.save(KnowledgeAnchorEntity(knowledgeId = saved.id!!, sceneName = sceneName))
        }
        return saved.id!!
    }

    suspend fun anchor(knowledgeId: Long, sceneName: String, screenId: Long? = null) {
        anchors.save(KnowledgeAnchorEntity(knowledgeId = knowledgeId, sceneName = sceneName, screenId = screenId))
    }
}
