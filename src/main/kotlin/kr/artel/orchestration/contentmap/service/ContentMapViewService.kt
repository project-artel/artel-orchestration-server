package kr.artel.orchestration.contentmap.service

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ContentMapEdgeResponse
import kr.artel.orchestration.contentmap.dto.ContentMapResponse
import kr.artel.orchestration.contentmap.dto.ContentMapSceneResponse
import kr.artel.orchestration.contentmap.dto.ContentMapSummaryResponse
import kr.artel.orchestration.contentmap.dto.PendingDocumentResponse
import kr.artel.orchestration.contentmap.dto.SceneCapabilityCountResponse
import kr.artel.orchestration.contentmap.dto.SpecGapCountResponse
import kr.artel.orchestration.contentmap.dto.VerificationResponse
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import org.springframework.stereotype.Service

/**
 * 사람이 브라우저에서 **씬 명세를 읽는** 자리. `content_map` 을 프로덕션에서 처음으로 읽는 코드다.
 *
 * 지금까지 이 표들을 읽은 것은 테스트뿐이었다 — 스키마(V40~V48)도, 조인도, 적재기도 행을 앉히기만
 * 하고 아무도 보지 않았다. 이 서비스가 그 창을 연다.
 *
 * **섹션마다 질의 하나**다. 한 방에 답하려면 씬·간선·gap·문서를 한 질의에 접어야 하는데 서로
 * 카디널리티가 달라 조인하면 행이 곱해지고, `json_agg` 로 도망가면 응답 모양이 SQL 문자열 안으로
 * 숨는다. 전부 `content_map_id` 하나로 좁힌 조회이고 한 화면이 한 번 부른다.
 *
 * 접근 검사가 컨트롤러가 아니라 여기 있는 이유: 이 표를 읽는 유일한 문이 이 함수라야, 이 함수를
 * 부르는 누구도 검사를 빠뜨릴 수 없다. `EvidenceDocumentService` · `ContentMapIngestService` 와 같은
 * 자리다.
 */
@Service
class ContentMapViewService(
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val scenes: SceneRepository,
    private val capabilities: CapabilityRepository,
    private val sceneEdges: SceneEdgeRepository,
    private val documents: ContentMapDocumentRepository,
) {

    /**
     * 이 빌드의 씬 명세를 읽는다. 접근할 수 없는 빌드면 null(→ 404).
     *
     * 부재와 권한 없음을 같은 404 로 묶는 것은, 구분해서 알려주면 id 를 훑어 남의 빌드가 존재한다는
     * 사실을 알아낼 수 있기 때문이다. 경로의 projectId 까지 보는 것은 그것이 장식이 되지 않게 하려는
     * 것이다 — 안 보면 아무 프로젝트 id 나 넣어도 통과하고, 화면이 남의 프로젝트 빌드를 자기 것처럼
     * 보여 준다.
     *
     * @param capture null 이면 가장 최근에 알게 된 capture 를 고른다. 값이 있는데 그 지도가 없으면
     *   **폴백하지 않는다** — 폴백하면 화면이 editor 를 player 라고 그린다.
     */
    suspend fun read(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        capture: Capture?,
    ): ContentMapResponse? {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: return null

        val contentMap = selectContentMap(gameBuildId, capture) ?: return ContentMapResponse.EMPTY
        val contentMapId = contentMap.id!!

        // 문서 목록 **하나**가 두 칸을 답한다 — 마지막 적재 시각과 대기 문서. 질의를 둘로 나누면
        // 두 칸이 서로 다른 스냅샷을 보고, "적재됐는데 대기 중"인 화면이 나온다.
        val allDocuments = documents.findByContentMapIdOrderByReceivedAtDesc(contentMapId).toList()

        return ContentMapResponse(
            contentMap = summaryOf(contentMap, allDocuments),
            scenes = scenesOf(contentMapId),
            edges = sceneEdges.findByContentMapId(contentMapId).map(ContentMapEdgeResponse::of).toList(),
            gaps = gapsOf(contentMapId),
            verification = verificationOf(contentMapId),
            pendingDocuments = allDocuments
                .filter { it.ingestedAt == null }
                .map(::pendingOf),
        )
    }

    /**
     * 어느 지도를 볼 것인가.
     *
     * 기본값을 `updated_at` 이 아니라 id 로 고르는 이유: `content_map` 행은 같은 capture 를 다시
     * 등록해도 갱신만 되므로, 시각으로 고르면 옛 capture 를 한 번 다시 올린 것만으로 기본값이
     * 뒤집힌다. id 는 "언제 이 capture 를 처음 알았나"라 그렇게 흔들리지 않는다.
     */
    private suspend fun selectContentMap(gameBuildId: Long, capture: Capture?): ContentMapEntity? =
        if (capture == null) {
            contentMaps.findByGameBuildIdOrderByIdDesc(gameBuildId).firstOrNull()
        } else {
            contentMaps.findByGameBuildIdAndCapture(gameBuildId, capture.wire)
        }

    /**
     * 씬과 그 상태 분포.
     *
     * 기능이 한 줄도 없는 씬은 집계에 행이 없다(`GROUP BY` 라). 그 씬을 목록에서 빼지 않고 0 으로
     * 채우는 것이 요점이다 — `walked=false` 인 씬은 비어 있는 것이 **정상**이고, 목록에서 사라지면
     * 화면이 그 씬의 존재 자체를 모른다.
     */
    private suspend fun scenesOf(contentMapId: Long): List<ContentMapSceneResponse> {
        val counts = capabilities.countByScene(contentMapId).toList().associateBy { it.sceneId }
        return scenes.findByContentMapIdOrderByNameAsc(contentMapId).toList().map { scene ->
            ContentMapSceneResponse(
                id = scene.id!!,
                name = scene.name,
                walked = scene.walked,
                capabilities = counts[scene.id]
                    ?.let(SceneCapabilityCountResponse::of)
                    ?: SceneCapabilityCountResponse.NONE,
            )
        }
    }

    /**
     * 사유별 분포. **집계는 Kotlin 에서 한다.**
     *
     * `GROUP BY reason` 질의를 따로 두지 않는 이유: `findSpecGaps` 가 이미 있는 창구이고
     * `reason IS NOT NULL` 필터를 그 한 곳이 소유한다. 사유 어휘가 늘 때 고칠 자리를 둘로 늘리지
     * 않는다. 행 수는 기능 수로 상한이 잡혀 있고, 그 행들을 만든 것은 1.4MB 문서 파싱이다.
     *
     * 많은 것부터 낸다. 이 표가 답하는 질문이 "다음에 무엇을 고칠까"라 순서가 곧 답이다. 수가 같으면
     * 사유 이름으로 갈라 응답이 호출마다 흔들리지 않게 한다.
     */
    private suspend fun gapsOf(contentMapId: Long): List<SpecGapCountResponse> =
        contentMaps.findSpecGaps(contentMapId).toList()
            .mapNotNull { it.reason }
            .groupingBy { it }
            .eachCount()
            .map { (reason, count) -> SpecGapCountResponse(reason, count) }
            .sortedWith(compareByDescending<SpecGapCountResponse> { it.count }.thenBy { it.reason })

    /** 기능이 하나도 없으면 집계 행 자체가 없다. 그때는 0/0 이며, 화면은 비율을 그리지 않는다. */
    private suspend fun verificationOf(contentMapId: Long): VerificationResponse =
        capabilities.countEvidenceVerification(contentMapId)
            ?.let { VerificationResponse(verified = it.verified, total = it.total) }
            ?: VerificationResponse(verified = 0, total = 0)

    /**
     * 지도 루트. 적재 시각은 **문서에서 유도한다.**
     *
     * `content_map` 에 적재 시각 칸이 없는 것은 적재기가 그 행을 건드리지 않기 때문이다 — 지문·
     * 유니티 버전·약속은 등록 경로가 소유하고, 두 곳이 같은 행을 쓰면 값을 두고 다툰다. 그 결정을
     * 되돌리는 대신 여기서 최댓값을 취한다.
     */
    private fun summaryOf(
        contentMap: ContentMapEntity,
        allDocuments: List<ContentMapDocumentEntity>,
    ) = ContentMapSummaryResponse(
        id = contentMap.id!!,
        capture = contentMap.capture,
        schemaVersion = contentMap.schemaVersion,
        evidenceDigest = contentMap.evidenceDigest,
        unity = contentMap.unity,
        platform = contentMap.platform,
        sdkVersion = contentMap.sdkVersion,
        ingestedAt = allDocuments.mapNotNull { it.ingestedAt }.maxOrNull(),
    )

    /** `received_at` 은 `NOT NULL DEFAULT CURRENT_TIMESTAMP` 라, 읽어 온 행에서는 항상 값이 있다. */
    private fun pendingOf(document: ContentMapDocumentEntity) = PendingDocumentResponse(
        documentId = document.id!!,
        receivedAt = document.receivedAt!!,
        ingestFailedAt = document.ingestFailedAt,
        ingestError = document.ingestError,
    )
}
