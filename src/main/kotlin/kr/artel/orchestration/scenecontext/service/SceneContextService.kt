package kr.artel.orchestration.scenecontext.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.dto.MacroSceneContextRow
import kr.artel.orchestration.contentmap.dto.MacroScreenRow
import kr.artel.orchestration.contentmap.entity.ContentMapMode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.macro.MacroWriteFrames
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.entity.SpecStatus
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.contentMapModeOf
import kr.artel.orchestration.scenecontext.dto.SceneCapabilityView
import kr.artel.orchestration.scenecontext.dto.SceneContextEntry
import kr.artel.orchestration.scenecontext.dto.SceneContextMacro
import kr.artel.orchestration.scenecontext.dto.SceneContextMacroParameter
import kr.artel.orchestration.scenecontext.dto.SceneContextMacroScreen
import kr.artel.orchestration.scenecontext.dto.SceneContextResponse
import kr.artel.orchestration.scenecontext.dto.SceneKnowledgeView
import kr.artel.orchestration.scenecontext.repository.AnchoredKnowledgeRepository
import kr.artel.orchestration.scenecontext.repository.AnchoredKnowledgeRow
import org.springframework.stereotype.Service

/**
 * 응답에 싣는 `macro` 수의 상한(ARTEL-934).
 *
 * 이 payload 는 **시나리오를 시작할 때마다** 프롬프트에 실려 토큰 값을 낸다. 상한이 없으면 빌드에
 * `macro` 가 쌓이는 만큼 모든 시나리오의 시작 비용이 같이 커진다. 30 은 실측으로 고른 값이 아니라
 * 그 부피에 천장을 두는 값이다 — 로컬 DB 의 가장 큰 지도가 9 개다. 넘으면 이름순 앞 30 개만 보내고
 * 나머지는 `macrosTotal` 로 잘렸다는 사실만 알린다.
 */
internal const val MAX_SCENE_CONTEXT_MACROS: Int = 30

/**
 * QA agent 가 런 시작에 한 번 받아 가는 **씬별 맥락**을 조립한다(ARTEL-611).
 *
 * 두 반쪽을 합친다. 하나는 content map 의 capability("여기서 무엇을 할 수 있나"), 다른 하나는
 * `knowledge_anchor` 가 씬에 묶은 지식("여기서만 참인 것"). 둘 다 이미 DB 에 있지만 agent 는
 * 그것을 도구 호출로 물어야 알았고, 물을 생각을 해야 물을 수 있었다.
 *
 * ## 씬 수와 무관하게 질의 수가 고정이다
 *
 * 씬마다 질의를 돌리면 씬 수백 개짜리 지도에서 런 시작이 그만큼 느려지고, 그 비용은 앵커가
 * 하나도 없는 프로젝트에서도 똑같이 난다. 그래서 지도 단위·프로젝트 단위로 **한 번씩만** 읽고
 * 씬별로 접는 일은 전부 메모리에서 한다.
 *
 * | # | 무엇 | 언제 |
 * |---|---|---|
 * | 1 | `qa_try` (스코프 · content map 모드 해석) | `qaTryId` 가 왔을 때만 |
 * | 2 | `game_build` (존재 + 프로젝트 대조) | 항상 |
 * | 3 | `content_map` 고르기 | `content_map_mode` 가 `off` 가 아닐 때만 |
 * | 4 | `scene` 목록 | 지도가 있을 때만 |
 * | 5 | `v_content_map_capability` 전량 | 지도가 있을 때만 |
 * | 6 | 앵커 + 지식 요약 | 항상 |
 * | 7 | `macro` 최대 30 개 (이름순, 전체 수 포함) | 지도가 있을 때만 |
 * | 8 | `macro` 에 달린 `screen` 관계 | 지도가 있을 때만 |
 *
 * ## 접근 검사가 컨트롤러가 아니라 여기 있다
 *
 * `ContentMapViewService.read` 와 같은 자리다. 이 표들을 읽는 문이 이 함수 하나라야, 다음
 * 진입점이 검사를 빠뜨릴 수 없다.
 */
@Service
class SceneContextService(
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val scenes: SceneRepository,
    private val macros: MacroRepository,
    private val screenMacros: ScreenMacroRepository,
    private val anchoredKnowledge: AnchoredKnowledgeRepository,
    private val qaTries: QaTryRepository,
    private val objectMapper: ObjectMapper,
) {

    /**
     * 이 빌드의 씬별 맥락을 읽는다. 빌드가 없거나 경로의 [projectId] 와 어긋나면 null(→ 404).
     *
     * **`evidence` 가 하나도 없는 빌드는 404 가 아니다.** 빌드는 존재하고, 없는 것은 아직 아무도 올리지
     * 않은 문서다. 그때 `scenes` 는 앵커가 든 씬만으로 채워지거나 통째로 빈다 — 둘 다 정상이고,
     * agent 는 "아직 지도가 없다"를 응답의 `contentMapId == null` 로 읽는다.
     *
     * 부재와 프로젝트 불일치를 같은 null 로 답하는 것은 브라우저 조회와 같은 이유다. 구분해서
     * 알려주면 id 를 훑어 남의 빌드가 존재한다는 사실을 알아낼 수 있다. 이 경로가 무인증
     * 내부망 전용이라 해도, 그 사실을 이유로 검사를 느슨하게 두면 경로의 `projectId` 는 장식이
     * 되고 다음 호출자가 그것을 믿는다.
     *
     * **`content_map_mode = off` 인 런도 같은 답이다.** 지도가 없는 빌드와 글자까지 같은 응답을 내는
     * 것이 그 대조군의 요점이다 — 없는 상태를 흉내 내는 것이 아니라, 이미 정상으로 취급되고 있는
     * 그 답을 그대로 낸다. 앵커 지식은 그때도 나온다.
     *
     * @param qaTryId 이 조회를 부른 QA 런. 지식 스코프와 content map 모드를 여기서 읽는다
     *   (ARTEL-256). null 이면 운영 스코프에 `content_map_mode = on` 이다. 없는 런 id 면 404 —
     *   조용히 운영 스코프로 떨어지면 실험 런이 운영 지식을 읽고도 아무도 못 알아챈다.
     */
    suspend fun read(projectId: Long, gameBuildId: Long, qaTryId: Long?): SceneContextResponse? {
        val gate = resolveGate(qaTryId)

        val build = gameBuilds.findById(gameBuildId) ?: return null
        if (build.projectId != projectId) return null

        // 지도가 없어도 앵커는 답한다. 앵커는 프로젝트에 매달린 사실이라 빌드에 지도가 있는지와
        // 수명이 다르다 — 지도가 없다고 그 사실이 없어지지는 않는다.
        val knowledgeByScene = anchoredKnowledge.findAnchoredKnowledge(projectId, gate.knowledgeScope.id)
            .toList()
            .groupBy(AnchoredKnowledgeRow::sceneName)

        // `content_map_mode = off` 면 지도를 조회조차 하지 않는다. 읽고 나서 비우는 것보다 이쪽이
        // 정직하다 — 이 branch 가 내는 답이 아래 "지도가 없는 빌드" 의 답과 **같은 식**이라,
        // 대조군이 흉내가 아니라 이미 정상으로 취급되는 상태 그대로다.
        //
        // **앵커 지식은 그대로 낸다.** 앵커는 지식창고에서 오는 것이지 지도에서 오는 것이 아니고,
        // 여기서 함께 지우면 두 축이 섞여 2×2 가 성립하지 않는다. 지식 축은 `knowledge_mode` 가
        // 따로 끈다.
        if (!gate.contentMapMode.readable) {
            return SceneContextResponse(
                gameBuildId = gameBuildId.toString(),
                scenes = anchorOnlyScenes(knowledgeByScene, emptySet()),
            )
        }

        // 빌드마다 지도가 하나라 고를 것이 없다(ARTEL-642). `ContentMapViewService` 와 **같은
        // 리포지토리 메서드**를 부르므로, 사람이 보는 지도와 agent 가 쓰는 지도가 갈릴 수 없다.
        val contentMap = contentMaps.findByGameBuildId(gameBuildId)
            ?: return SceneContextResponse(
                gameBuildId = gameBuildId.toString(),
                scenes = anchorOnlyScenes(knowledgeByScene, emptySet()),
            )
        val contentMapId = contentMap.id!!

        val mapScenes = scenes.findByContentMapIdOrderByNameAsc(contentMapId).toList()
        // 이 빌드의 capability 전부를 읽는다(ARTEL-680). `not-a-step` 을 거르는 것은 TC 생성기의
        // 사정이고 agent 의 사정이 아니다 — 걸러진 목록으로는 agent 가 그 행들을 지목할 수 없다.
        val capabilitiesByScene = contentMaps.findAllCapabilityRows(contentMapId)
            .toList()
            .groupBy(ContentMapCapabilityRow::sceneName)
        // `macro` 를 **먼저** 읽고 `screen` 관계를 나중에 읽는다. 둘이 한 트랜잭션이 아니라서 그 사이에
        // 등록된 `macro` 가 있을 수 있다. 관계를 먼저 읽으면 그 `macro` 가 빈 `screens` 로 나가고,
        // 이 응답에서 빈 `screens` 는 "아직 어디서 쓸지 모른다" 는 주장이라 거짓이 된다. 순서를
        // 이렇게 두면 그 `macro` 는 이번 응답에 안 서고 다음 조회에 제대로 선다(`MacroViewService.list`).
        val macroRows = macros.findSceneContextRowsByContentMapId(contentMapId, MAX_SCENE_CONTEXT_MACROS).toList()
        val screensByMacroId = screenRowsOf(contentMapId, macroRows)

        return SceneContextResponse(
            gameBuildId = gameBuildId.toString(),
            contentMapId = contentMapId.toString(),
            capture = contentMap.capture,
            scenes = mapScenes.map { scene -> entryOf(scene, capabilitiesByScene, knowledgeByScene) } +
                anchorOnlyScenes(knowledgeByScene, mapScenes.mapTo(mutableSetOf(), SceneEntity::name)),
            macros = macroRows.map { macroOf(it, screensByMacroId[it.id].orEmpty()) },
            macrosTotal = macroRows.firstOrNull()?.total?.toInt() ?: 0,
        )
    }

    /**
     * 읽어 온 `macro` 들에 달린 `screen` 관계를 `macro` id 로 묶는다. `macro` 가 없으면 질의를 돌리지 않는다.
     *
     * 지도 전체의 관계를 읽고 [macroRows] 에 든 `macro` 만 남긴다. 30 개 밖의 `macro` 관계를 읽는 낭비가
     * 있으나 `screen_macro` 는 좁은 표이고, 같은 질의를 사람이 보는 목록(`MacroViewService`)이 이미
     * 쓰므로 두 길이 같은 `screen` 이름을 낸다. 질의 순서가 `macro_id, screen.id` 라 묶은 뒤에도 각
     * `macro` 의 `screen` 이 id 오름차순이다.
     */
    private suspend fun screenRowsOf(
        contentMapId: Long,
        macroRows: List<MacroSceneContextRow>,
    ): Map<Long, List<MacroScreenRow>> {
        if (macroRows.isEmpty()) return emptyMap()
        val readMacroIds = macroRows.mapTo(mutableSetOf(), MacroSceneContextRow::id)
        return screenMacros.findScreenRowsByContentMapId(contentMapId)
            .toList()
            .filter { it.macroId in readMacroIds }
            .groupBy(MacroScreenRow::macroId)
    }

    /**
     * `macro` 한 줄. `screens` 가 비어도 **싣는다** — 비어 있다는 것은 "아직 어디서 쓸지 모른다" 이지
     * "없다" 가 아니고, 빼면 agent 는 이전 런이 등록한 `macro` 를 못 찾는다.
     */
    private fun macroOf(row: MacroSceneContextRow, screens: List<MacroScreenRow>) = SceneContextMacro(
        name = row.name,
        parameters = parametersOf(row.declaredParameters),
        summary = row.docstring,
        screens = screens.map { SceneContextMacroScreen(screenId = it.screenId.toString(), sceneName = it.sceneName) },
    )

    /**
     * 진입점 `def` 의 parameter 를 **선언한 순서 그대로** 읽는다. 이름이 없는 항목은 건너뛰고, 타입을
     * 못 읽는 항목은 `unknown` 으로 싣는다 — 이름과 개수와 순서가 호출 모양이라 타입이 비었다고 빼면
     * agent 가 인자 개수를 틀린다.
     */
    private fun parametersOf(declared: Json?): List<SceneContextMacroParameter> {
        val parameters = declared
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf(JsonNode::isArray)
            ?: return emptyList()
        return parameters.mapNotNull { parameter ->
            val name = parameter.path(MacroWriteFrames.PARAMETER_NAME_FIELD)
                .takeIf(JsonNode::isTextual)?.asText() ?: return@mapNotNull null
            val type = parameter.path(MacroWriteFrames.DECLARED_TYPE_FIELD)
                .takeIf(JsonNode::isTextual)?.asText() ?: UNKNOWN_PARAMETER_TYPE
            SceneContextMacroParameter(name = name, type = type)
        }
    }

    /**
     * 지도가 아는 씬 한 줄. capability 가 하나도 없어도 **목록에서 빼지 않는다.**
     *
     * `walked=false` 인 씬은 비어 있는 것이 정상이고(V40), 그 씬이 목록에서 사라지면 agent 는
     * "지도에 있지만 아직 안 걸어 본 씬"과 "지도가 모르는 씬"을 구분할 수 없다. 빈 줄의 값은
     * 프롬프트에서 거의 0 이고, 없는 줄의 대가는 agent 의 오판이다.
     *
     * 한 씬의 행을 `status` 로 두 목록으로 가른다. 질의를 하나 더 돌리지 않는다 — 씬 수와 무관하게
     * 질의 수를 고정한 것이 이 서비스의 설계이고, 나누는 일은 이미 읽어 온 목록에서 한다.
     * [partition] 이 원래 순서를 지키므로 뷰 질의의 `ORDER BY` 가 두 목록에 그대로 남는다.
     */
    private fun entryOf(
        scene: SceneEntity,
        capabilitiesByScene: Map<String, List<ContentMapCapabilityRow>>,
        knowledgeByScene: Map<String, List<AnchoredKnowledgeRow>>,
    ): SceneContextEntry {
        val (notASteps, steps) = capabilitiesByScene[scene.name].orEmpty()
            .partition { it.status == SpecStatus.NOT_A_STEP.wire }
        return SceneContextEntry(
            sceneName = scene.name,
            knownToContentMap = true,
            origin = scene.origin,
            sceneSummary = scene.summary,
            capabilities = foldByEntry(steps),
            notAStepCapabilities = foldByEntry(notASteps),
            knowledge = knowledgeByScene[scene.name].orEmpty().map(::knowledgeOf),
        )
    }

    /**
     * 사람이 "기능 하나" 로 세는 단위로 접는다.
     *
     * **적재의 정체로 세면 안 된다.** `CapabilityKey` 는 효과가 사는 메서드(`method_id`)까지
     * 넣고, 넣어야만 한다 — 그 KDoc 의 표가 왜인지를 적어 두었고, `scene_edge.capability_id` 와
     * `screen_transition.capability_id` 가 그 번호를 참조한다. 그래서 **적재는 그대로 두고 읽는
     * 쪽에서 접는다.**
     *
     * 접는 열쇠는 `entry_id` 다. `MapTestCaseGenerator` 가 같은 문제를 이미 이 값으로 풀었다 —
     * "플레이어가 무엇을 건드렸나. 이것이 곧 사람이 기능 하나로 세는 단위다".
     *
     * **왜 접나.** 실측 `Canvas/MapSceneButton` 이 한 `scene` 에서 7 줄이고, 일곱 줄의
     * `control_selector` 도 `interaction` 도 같고 `given_text` 는 비어 있다. 가르는 축이
     * `summary` 에 박힌 메서드 이름 하나뿐이다. agent 는 그 버튼을 눌러 화면이 바뀐 것만
     * 보므로 일곱 중 어디에 판정을 찍을지 고를 수가 없고, 실제로 한 번도 안 찍었다
     * (`capability_observation` 이 0 행이었다, ARTEL-790).
     *
     * 접으면 목록도 함께 줄어든다. 블록은 자리가 좁아 잘리는데(`showing 8 of 14`) 그 자리를
     * 중복이 먹고 있었다 — 실측으로 1,300 행이 319 로 접힌다.
     *
     * `entry_id` 가 없는 행은 근거 출신이 아니다. 그때는 적재의 키로 물러서므로 접히지 않는다.
     */
    private fun foldByEntry(rows: List<ContentMapCapabilityRow>): List<SceneCapabilityView> =
        rows.groupBy { it.entryId ?: "\u0000${it.capabilityId}" }
            .map { (_, group) -> capabilityOf(representativeOf(group), covers = group.size) }

    /**
     * 접힌 무리를 대표할 행.
     *
     * **관측 가능한 쪽을 고른다.** 무리의 `summary` 는 전부 같은 컨트롤을 가리키지만 이름은
     * 각자 다른 메서드다. 그중 `observable` 인 줄이 agent 가 화면에서 볼 수 있는 것에 가장
     * 가깝고, 그 이름이 줄의 제목이 되어야 자기가 본 것과 맞댈 수 있다. 실측
     * `MapSceneButton` 의 일곱 중 둘이 `observable` 이다.
     *
     * 없으면 첫 줄이다. 뷰 질의의 `ORDER BY` 가 그 순서를 정하므로 같은 지도에서 같은 답이 난다.
     */
    private fun representativeOf(group: List<ContentMapCapabilityRow>): ContentMapCapabilityRow =
        group.firstOrNull { it.observability == Observability.OBSERVABLE.wire } ?: group.first()

    /**
     * 지도가 들어 본 적 없는 씬 이름을 든 앵커. **이것을 버리지 않는 것이 이 응답의 요점 하나다.**
     *
     * 앵커는 content map 과 대조하지 않고 저장된다(ARTEL-591, V55) — 지도가 없는 프로젝트에도 씬
     * 이름은 있기 때문이다. 그래서 지도에 없는 씬을 가리키는 앵커는 오류가 아니라 **정상**이고,
     * 여기서 떨어뜨리면 agent 는 자기가 이미 배운 사실을 잃는다. 그 손실은 조용하다.
     *
     * `knownToContentMap = false` 로 구분해 낸다. 지도가 아는 씬인지 아닌지를 agent 가 알아야,
     * capability 가 비어 있는 것을 "할 게 없다"로 읽을지 "아직 안 걸어 봤다"로 읽을지 고를 수 있다.
     */
    private fun anchorOnlyScenes(
        knowledgeByScene: Map<String, List<AnchoredKnowledgeRow>>,
        mapSceneNames: Set<String>,
    ): List<SceneContextEntry> =
        knowledgeByScene.keys
            .filterNot { it in mapSceneNames }
            .sorted()
            .map { sceneName ->
                SceneContextEntry(
                    sceneName = sceneName,
                    knownToContentMap = false,
                    knowledge = knowledgeByScene.getValue(sceneName).map(::knowledgeOf),
                )
            }

    private fun capabilityOf(row: ContentMapCapabilityRow, covers: Int = 1) = SceneCapabilityView(
        capabilityId = row.capabilityId.toString(),
        capabilityKey = row.capabilityKey,
        summary = row.summary,
        given = row.conditionTree
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isObject && !it.isEmpty }
            ?.let { runCatching { EvidenceParser(objectMapper).parseCondition(it) }.getOrNull() }
            ?.let(kr.artel.orchestration.contentmap.dto.ConditionNodeResponse::of),
        interaction = row.interaction,
        inputKey = row.inputKey,
        controlPath = row.controlPath,
        controlLabel = row.controlLabel,
        status = row.status,
        actionability = row.actionability,
        observability = row.observability,
        applicability = row.applicability,
        verification = row.verification,
        scenePresence = row.scenePresence,
        repeatUntilDone = row.repeatUntilDone,
        controlSelectorHint = row.controlSelector,
        covers = covers,
    )

    private fun knowledgeOf(row: AnchoredKnowledgeRow) = SceneKnowledgeView(
        knowledgeId = row.knowledgeId.toString(),
        summary = row.summary,
    )

    /**
     * 이 조회에 걸리는 두 게이트를 런에서 읽는다. **`knowledgeScopeId` 나 모드를 파라미터로 열지
     * 않는 이유**는 `KnowledgeController` 가 같은 이유로 스코프 id 를 거절한 것과 같다 — 그 형식이
     * API 표면에 먼저 생기면 실험 엔티티가 그것을 따라가는 순서가 된다.
     *
     * agent 는 세션 개설 payload 로 `qa_try_id` 를 이미 받는다(`QaAgentScenario.qaTryId`). 그
     * 값에서 둘 다 읽으면 호출자가 게이트를 지어낼 수 없고, 런과 그 런이 읽은 것이 같은 행에서
     * 나온다.
     *
     * 한 번의 `findById` 로 둘을 함께 읽는다. 따로 읽으면 질의가 하나 더 늘고, 그 사이에 런의
     * `run_config` 가 바뀌면 한 응답 안에서 두 게이트가 서로 다른 시점을 본다.
     */
    private suspend fun resolveGate(qaTryId: Long?): SceneContextGate {
        if (qaTryId == null) return SceneContextGate(KnowledgeScope.PRODUCTION, ContentMapMode.DEFAULT)
        val qaTry = qaTries.findById(qaTryId)
            ?: throw NotFoundException("QA 런을 찾을 수 없습니다.")
        return SceneContextGate(
            knowledgeScope = KnowledgeScope.of(qaTry.knowledgeScopeId),
            contentMapMode = contentMapModeOf(qaTry, objectMapper),
        )
    }
}

private const val UNKNOWN_PARAMETER_TYPE = "unknown"

/**
 * 한 조회에 걸리는 두 게이트. 축이 둘이라 묶어 든다.
 *
 * `qaTryId` 가 없는 조회(운영 조회)는 운영 스코프에 [ContentMapMode.DEFAULT] 다 — 게이트는 런에
 * 매달린 것이고, 런이 없으면 끌 것도 없다.
 */
private data class SceneContextGate(
    val knowledgeScope: KnowledgeScope,
    val contentMapMode: ContentMapMode,
)
