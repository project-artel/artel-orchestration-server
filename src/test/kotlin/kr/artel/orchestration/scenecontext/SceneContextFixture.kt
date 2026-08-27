package kr.artel.orchestration.scenecontext

import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
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

    suspend fun newScene(contentMapId: Long, name: String, summary: String? = null): Long =
        scenes.save(
            SceneEntity(contentMapId = contentMapId, name = name, summary = summary, walked = true)
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
        ).id!!

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
