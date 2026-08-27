package kr.artel.orchestration.scenecontext.service

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.scenecontext.dto.SceneCapabilityView
import kr.artel.orchestration.scenecontext.dto.SceneContextEntry
import kr.artel.orchestration.scenecontext.dto.SceneContextResponse
import kr.artel.orchestration.scenecontext.dto.SceneKnowledgeView
import kr.artel.orchestration.scenecontext.repository.AnchoredKnowledgeRepository
import kr.artel.orchestration.scenecontext.repository.AnchoredKnowledgeRow
import org.springframework.stereotype.Service

/**
 * QA agent 가 런 시작에 한 번 받아 가는 **씬별 맥락**을 조립한다(ARTEL-611).
 *
 * 두 반쪽을 합친다. 하나는 content map 의 capability("여기서 무엇을 할 수 있나"), 다른 하나는
 * `knowledge_anchor` 가 씬에 묶은 지식("여기서만 참인 것"). 둘 다 이미 DB 에 있지만 agent 는
 * 그것을 도구 호출로 물어야 알았고, 물을 생각을 해야 물을 수 있었다.
 *
 * ## 씬 수와 무관하게 질의 수가 고정이다
 *
 * 씬마다 질의를 돌리면 씬 수백 개짜리 지도에서 런 시작이 그만큼 느려지고, 그 비용은 `anchor` 가
 * 하나도 없는 프로젝트에서도 똑같이 난다. 그래서 지도 단위·프로젝트 단위로 **한 번씩만** 읽고
 * 씬별로 접는 일은 전부 메모리에서 한다.
 *
 * | # | 무엇 | 언제 |
 * |---|---|---|
 * | 1 | `qa_try` (스코프 해석) | `qaTryId` 가 왔을 때만 |
 * | 2 | `game_build` (존재 + 프로젝트 대조) | 항상 |
 * | 3 | `content_map` 고르기 | 항상 |
 * | 4 | `scene` 목록 | 지도가 있을 때만 |
 * | 5 | `v_content_map_capability` 전량 | 지도가 있을 때만 |
 * | 6 | `anchor` + 지식 요약 | 항상 |
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
    private val anchoredKnowledge: AnchoredKnowledgeRepository,
    private val qaTries: QaTryRepository,
) {

    /**
     * 이 빌드의 씬별 맥락을 읽는다. 빌드가 없거나 경로의 [projectId] 와 어긋나면 null(→ 404).
     *
     * **`evidence` 가 하나도 없는 빌드는 404 가 아니다.** 빌드는 존재하고, 없는 것은 아직 아무도 올리지
     * 않은 문서다. 그때 `scenes` 는 `anchor` 가 든 씬만으로 채워지거나 통째로 빈다 — 둘 다 정상이고,
     * agent 는 "아직 지도가 없다"를 응답의 `contentMapId == null` 로 읽는다.
     *
     * 부재와 프로젝트 불일치를 같은 null 로 답하는 것은 브라우저 조회와 같은 이유다. 구분해서
     * 알려주면 id 를 훑어 남의 빌드가 존재한다는 사실을 알아낼 수 있다. 이 경로가 무인증
     * 내부망 전용이라 해도, 그 사실을 이유로 검사를 느슨하게 두면 경로의 `projectId` 는 장식이
     * 되고 다음 호출자가 그것을 믿는다.
     *
     * @param qaTryId 이 조회를 부른 QA 런. 지식 스코프를 여기서 읽는다(ARTEL-256). null 이면 운영
     *   스코프다. 없는 런 id 면 404 — 조용히 운영 스코프로 떨어지면 실험 런이 운영 지식을 읽고도
     *   아무도 못 알아챈다.
     */
    suspend fun read(projectId: Long, gameBuildId: Long, qaTryId: Long?): SceneContextResponse? {
        val scope = resolveScope(qaTryId)

        val build = gameBuilds.findById(gameBuildId) ?: return null
        if (build.projectId != projectId) return null

        // 지도가 없어도 `anchor` 는 답한다. `anchor` 는 프로젝트에 매달린 사실이라 빌드에 지도가 있는지와
        // 수명이 다르다 — 지도가 없다고 그 사실이 없어지지는 않는다.
        val knowledgeByScene = anchoredKnowledge.findAnchoredKnowledge(projectId, scope.id)
            .toList()
            .groupBy(AnchoredKnowledgeRow::sceneName)

        // 어느 지도를 볼 것인가 — **브라우저 조회와 같은 규칙이어야 한다.** `capture` 를 인자로
        // 받지 않고 가장 최근에 알게 된 것(id 내림차순)을 고르며, `ContentMapViewService` 의 기본
        // 분기와 **같은 리포지토리 메서드**를 부르므로 두 규칙이 갈릴 수 없다. 갈리면 사람이 보는
        // 지도와 agent 가 쓰는 지도가 달라지고, 그 어긋남은 QA 결과를 읽을 때 드러나지 않는다.
        //
        // `updated_at` 이 아니라 id 인 이유도 그쪽과 같다. 같은 capture 를 다시 등록해도 행은
        // 갱신만 되므로, 시각으로 고르면 옛 capture 를 한 번 다시 올린 것만으로 기본값이 뒤집힌다.
        val contentMap = contentMaps.findByGameBuildIdOrderByIdDesc(gameBuildId).firstOrNull()
            ?: return SceneContextResponse(
                gameBuildId = gameBuildId.toString(),
                scenes = anchorOnlyScenes(knowledgeByScene, emptySet()),
            )
        val contentMapId = contentMap.id!!

        val mapScenes = scenes.findByContentMapIdOrderByNameAsc(contentMapId).toList()
        val capabilitiesByScene = contentMaps.findCapabilityRows(contentMapId)
            .toList()
            .groupBy(ContentMapCapabilityRow::sceneName)

        return SceneContextResponse(
            gameBuildId = gameBuildId.toString(),
            contentMapId = contentMapId.toString(),
            capture = contentMap.capture,
            scenes = mapScenes.map { scene -> entryOf(scene, capabilitiesByScene, knowledgeByScene) } +
                anchorOnlyScenes(knowledgeByScene, mapScenes.mapTo(mutableSetOf(), SceneEntity::name)),
        )
    }

    /**
     * 지도가 아는 씬 한 줄. capability 가 하나도 없어도 **목록에서 빼지 않는다.**
     *
     * `walked=false` 인 씬은 비어 있는 것이 정상이고(V40), 그 씬이 목록에서 사라지면 agent 는
     * "지도에 있지만 아직 안 걸어 본 씬"과 "지도가 모르는 씬"을 구분할 수 없다. 빈 줄의 값은
     * 프롬프트에서 거의 0 이고, 없는 줄의 대가는 agent 의 오판이다.
     */
    private fun entryOf(
        scene: SceneEntity,
        capabilitiesByScene: Map<String, List<ContentMapCapabilityRow>>,
        knowledgeByScene: Map<String, List<AnchoredKnowledgeRow>>,
    ) = SceneContextEntry(
        sceneName = scene.name,
        knownToContentMap = true,
        sceneSummary = scene.summary,
        capabilities = capabilitiesByScene[scene.name].orEmpty().map(::capabilityOf),
        knowledge = knowledgeByScene[scene.name].orEmpty().map(::knowledgeOf),
    )

    /**
     * 지도가 들어 본 적 없는 씬 이름을 든 `anchor`. **이것을 버리지 않는 것이 이 응답의 요점 하나다.**
     *
     * `anchor` 는 content map 과 대조하지 않고 저장된다(ARTEL-591, V55) — 지도가 없는 프로젝트에도 씬
     * 이름은 있기 때문이다. 그래서 지도에 없는 씬을 가리키는 `anchor` 는 오류가 아니라 **정상**이고,
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

    private fun capabilityOf(row: ContentMapCapabilityRow) = SceneCapabilityView(
        capabilityId = row.capabilityId.toString(),
        capabilityKey = row.capabilityKey,
        summary = row.summary,
        givenText = row.givenText,
        interaction = row.interaction,
        inputKey = row.inputKey,
        controlPath = row.controlPath,
        controlLabel = row.controlLabel,
        status = row.status,
        actionability = row.actionability,
        observability = row.observability,
        applicability = row.applicability,
        verification = row.verification,
        repeatUntilDone = row.repeatUntilDone,
        controlSelectorHint = row.controlSelector,
    )

    private fun knowledgeOf(row: AnchoredKnowledgeRow) = SceneKnowledgeView(
        knowledgeId = row.knowledgeId.toString(),
        summary = row.summary,
    )

    /**
     * 스코프를 런에서 읽는다. **`knowledgeScopeId` 를 파라미터로 열지 않는 이유**는
     * `KnowledgeController` 가 같은 이유로 그것을 거절한 것과 같다 — 스코프 id 가 API 표면에 먼저
     * 생기면 실험 엔티티가 그 형식을 따라가는 순서가 된다.
     *
     * agent 는 세션 개설 payload 로 `qa_try_id` 를 이미 받는다(`QaAgentScenario.qaTryId`). 그
     * 값에서 스코프를 읽으면 호출자가 스코프를 지어낼 수 없고, 런과 그 런이 읽은 지식이 같은
     * 행에서 나온다.
     */
    private suspend fun resolveScope(qaTryId: Long?): KnowledgeScope {
        if (qaTryId == null) return KnowledgeScope.PRODUCTION
        val qaTry = qaTries.findById(qaTryId)
            ?: throw NotFoundException("QA 런을 찾을 수 없습니다.")
        return KnowledgeScope.of(qaTry.knowledgeScopeId)
    }
}
