package kr.artel.orchestration.contentmap.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.dto.ContentMapEdgeResponse
import kr.artel.orchestration.contentmap.dto.ContentMapResponse
import kr.artel.orchestration.contentmap.dto.ContentMapSceneEdgeRow
import kr.artel.orchestration.contentmap.dto.ContentMapSceneResponse
import kr.artel.orchestration.contentmap.dto.ContentMapScreenResponse
import kr.artel.orchestration.contentmap.dto.ContentMapScreenTransitionResponse
import kr.artel.orchestration.contentmap.dto.ContentMapSummaryResponse
import kr.artel.orchestration.contentmap.dto.LastScanResponse
import kr.artel.orchestration.contentmap.dto.PendingDocumentResponse
import kr.artel.orchestration.contentmap.dto.SceneCapabilityCountResponse
import kr.artel.orchestration.contentmap.dto.SceneCapabilityResponse
import kr.artel.orchestration.contentmap.dto.SceneStepResponse
import kr.artel.orchestration.contentmap.dto.SceneThumbnailResponse
import kr.artel.orchestration.contentmap.dto.ScreenImageResponse
import kr.artel.orchestration.contentmap.dto.SpecGapCountResponse
import kr.artel.orchestration.contentmap.dto.VerificationResponse
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.repository.ScreenTransitionRepository
import kr.artel.orchestration.contentmap.scan.ScanStatusRegistry
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.storage.DocumentStorage
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
    private val screens: ScreenRepository,
    private val screenTransitions: ScreenTransitionRepository,
    private val documents: ContentMapDocumentRepository,
    private val scanStatuses: ScanStatusRegistry,
    private val storage: DocumentStorage,
    private val objectMapper: ObjectMapper,
) {

    /**
     * `condition_tree` 를 되읽는 파서. **적재기와 같은 것을 쓴다.**
     *
     * 조건 정규화를 이 서비스에 한 벌 더 쓰지 않는 이유: 두 벌이 되면 **두 곳이 서로 다르게
     * 관대해진다.** 파서가 대문자 `EVERY` 나 이름표 없는 노드를 읽게 되는 날, 이 응답도 같은 날
     * 그것을 읽어야 한다.
     */
    private val evidence = EvidenceParser(objectMapper)

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

        // 마지막 스캔은 지도가 있든 없든 답한다. 지도가 없을 때야말로 화면이 그것을 물어야 한다 —
        // "아직 스캔한 적이 없다"와 "눌렀는데 게임이 실패로 답했다"가 여기서 갈린다.
        val lastScan = scanStatuses.find(gameBuildId)?.let(LastScanResponse::of)

        val contentMap = selectContentMap(gameBuildId, capture)
            ?: return ContentMapResponse.EMPTY.copy(lastScan = lastScan)
        val contentMapId = contentMap.id!!

        // 문서 목록 **하나**가 두 칸을 답한다 — 마지막 적재 시각과 대기 문서. 질의를 둘로 나누면
        // 두 칸이 서로 다른 스냅샷을 보고, "적재됐는데 대기 중"인 화면이 나온다.
        val allDocuments = documents.findByContentMapIdOrderByReceivedAtDesc(contentMapId).toList()

        return ContentMapResponse(
            contentMap = summaryOf(contentMap, allDocuments),
            scenes = scenesOf(contentMapId),
            edges = sceneEdges.findByContentMapId(contentMapId).map(::edgeOf).toList(),
            // 화면 전이는 씬 안에 접지 않는다. 씬 경계를 넘는 전이는 두 씬에 걸쳐 있어 어느 쪽에
            // 넣어도 반쪽이 되고, 화면은 그 선을 씬 컨테이너 **밖에** 그려야 한다.
            screenTransitions = screenTransitions.findByContentMapId(contentMapId)
                .map(ContentMapScreenTransitionResponse::of)
                .toList(),
            gaps = gapsOf(contentMapId),
            verification = verificationOf(contentMapId),
            pendingDocuments = allDocuments
                .filter { it.ingestedAt == null }
                .map(::pendingOf),
            lastScan = lastScan,
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
     * 씬과 그 상태 분포, 그리고 **무엇을 할 수 있는지의 목록.**
     *
     * 기능이 한 줄도 없는 씬은 집계에 행이 없다(`GROUP BY` 라). 그 씬을 목록에서 빼지 않고 0 으로
     * 채우는 것이 요점이다 — `walked=false` 인 씬은 비어 있는 것이 **정상**이고, 목록에서 사라지면
     * 화면이 그 씬의 존재 자체를 모른다.
     *
     * **카운트와 목록의 출처가 다르고, 달라야 한다.**
     *
     * | | 출처 | 왜 |
     * |---|---|---|
     * | 카운트 | `capability` 직접 집계 | 뷰가 `not-a-step` 을 걸러 내 그 칸이 구조적으로 0 이 된다 |
     * | 목록 | `v_content_map_capability` | 그 뷰가 곧 "무엇이 단계인가"의 정의다 |
     *
     * 목록 쪽 필터를 손으로 베껴 `capability` 를 직접 읽지 않는 이유: 베끼는 순간 단계의 정의가 두
     * 곳이 되고, 다음에 뷰가 필터를 하나 더 걸 때 화면만 낡는다. 게다가 그 뷰는 TC 생성기가 읽는
     * 창구라, 같은 창구를 읽는다는 것은 **화면이 보는 단계와 TC 생성기가 받는 단계가 갈릴 수 없다**는
     * 뜻이기도 하다.
     *
     * 두 출처가 만나는 등식이 그래서 성립한다: `steps.size == total - notAStep`.
     *
     * **기능 목록은 세 번째 출처가 아니다.** `capabilityList` 는 카운트와 **같은 질의**에서 나온다 —
     * `findSceneCapabilities` 가 `countByScene` 의 필터와 조인을 그대로 쓰므로
     * `capabilityList.size == total` 이 구조적으로 참이다. 목록을 뷰에서 가져오면 그 등식이 깨지고,
     * 인스펙터가 설명해야 할 `not-a-step` 행이 통째로 사라진다.
     *
     * 화면은 씬마다 도는 대신 지도 한 번으로 읽어 묶는다. 씬 수만큼 왕복을 내지 않으려는 것이고,
     * 화면이 0 행인 지도(= QA 런 전의 모든 빌드)에서 그 왕복은 전부 헛것이다.
     */
    private suspend fun scenesOf(contentMapId: Long): List<ContentMapSceneResponse> {
        val counts = capabilities.countByScene(contentMapId).toList().associateBy { it.sceneId }
        val steps = stepsByScene(contentMapId)
        val capabilityLists = capabilities.findSceneCapabilities(contentMapId).toList()
            .groupBy({ it.sceneId }, SceneCapabilityResponse::of)
        val screensByScene = screens.findByContentMapId(contentMapId).toList()
            .groupBy({ it.sceneId }, ::screenOf)
        return scenes.findByContentMapIdOrderByNameAsc(contentMapId).toList().map { scene ->
            ContentMapSceneResponse(
                id = scene.id!!,
                name = scene.name,
                walked = scene.walked,
                capabilities = counts[scene.id]
                    ?.let(SceneCapabilityCountResponse::of)
                    ?: SceneCapabilityCountResponse.NONE,
                steps = steps[scene.id].orEmpty(),
                thumbnail = thumbnailOf(scene),
                screens = screensByScene[scene.id].orEmpty(),
                capabilityList = capabilityLists[scene.id].orEmpty(),
            )
        }
    }

    /**
     * 화면 한 줄. **QA 런이 아직 없으면 이 함수는 한 번도 불리지 않는다.**
     *
     * `screen` 이 0 행인 빌드가 오류가 아니라는 것이 이 경로의 요점이다 — 정적 분석은 화면을 알
     * 수 없고, 화면을 앉히는 것은 QA 런뿐이다. 그때도 씬은 그대로 나가야 한다.
     *
     * `discriminator` 를 파싱해 모양을 못 박지 않는다. 서버는 이 값을 **읽지 않고** 화면이 사람에게
     * 그대로 보여 준다. 여기서 DTO 로 좁히면 관측 쪽이 판정 어휘를 하나 늘리는 날 조회가 먼저
     * 깨지고, 깨지는 이유는 서버가 쓰지도 않는 필드다. `jsonb` 컬럼이라 `readTree` 는 실패할 수
     * 없다 — DB 가 이미 JSON 임을 보증한다.
     */
    private fun screenOf(screen: ScreenEntity) = ContentMapScreenResponse(
        id = screen.id!!,
        sceneId = screen.sceneId,
        name = screen.name,
        discriminator = objectMapper.readTree(screen.discriminator.asString()),
        observedCount = screen.observedCount,
        firstSeenQaRunId = screen.firstSeenQaRunId,
        image = imageOf(screen),
    )

    /**
     * 화면 캡처를 서명된 단기 주소로 바꾼다. **씬 대표 이미지와 같은 경로다**([thumbnailOf]).
     *
     * 서명 방식을 하나로 두는 이유: 두 벌이 되면 TTL·헤더·`Content-Disposition` 이 서로 다르게
     * 흘러가고, 화면은 어느 쪽 주소를 쥐었는지에 따라 다르게 동작한다. 바이트를 이 서버로 끌어와
     * 중계하지 않는 것도 같다 — 화면이 수백 개인 지도에서 그만큼이 응답 하나에 실린다.
     *
     * 파일 이름을 이름 없는 화면에서 id 로 짓는 것은 `screen.name` 이 nullable 이기 때문이다. 이
     * 값은 내려받을 때의 파일 이름일 뿐이고 조인 키가 아니다.
     */
    private fun imageOf(screen: ScreenEntity): ScreenImageResponse? =
        screen.imageObjectKey?.let { objectKey ->
            val signed = storage.presignDownload(objectKey, "${screen.name ?: "screen-${screen.id}"}.jpg")
            ScreenImageResponse(
                url = signed.url,
                expiresAt = signed.expiresAt,
                capturedAt = screen.imageCapturedAt,
            )
        }

    /**
     * 씬 이미지를 서명된 단기 주소로 바꾼다.
     *
     * 바이트를 이 서버로 끌어와 중계하지 않는다 — 씬이 수백 개인 지도에서 그 만큼의 이미지가
     * 응답 하나에 실린다. 대신 스토리지가 직접 주게 하고, 여기서는 주소만 만든다.
     */
    private fun thumbnailOf(scene: SceneEntity): SceneThumbnailResponse? {
        scene.imageObjectKey?.let { objectKey ->
            val signed = storage.presignDownload(objectKey, "${scene.name}.jpg")
            return SceneThumbnailResponse(
                state = "available",
                url = signed.url,
                expiresAt = signed.expiresAt,
                width = scene.imageWidth,
                height = scene.imageHeight,
            )
        }
        return scene.imageFailureCode?.let { reason ->
            SceneThumbnailResponse(state = "unavailable", reason = reason)
        }
    }

    /**
     * 간선에 정규화된 전이 조건을 붙인다.
     *
     * `given_text` 는 사람이 읽는 한 줄이라 화면이 `branch` 를 구분하는 데 쓸 수 없다. 같은 컨트롤이
     * 조건으로 갈릴 때 무엇이 다른지는 조건 트리에만 있다. `capability_evidence` 는 기능당 한 행이
     * (PRIMARY KEY) 라 이 조인이 간선을 늘리지 않는다.
     */
    private fun edgeOf(row: ContentMapSceneEdgeRow): ContentMapEdgeResponse {
        val given = row.conditionTree
            ?.let { ConditionNodeResponse.of(evidence.parseCondition(objectMapper.readTree(it.asString()))) }
        return ContentMapEdgeResponse.of(row, given)
    }

    /**
     * 조작 단계를 씬별로 묶는다. **기능 하나에 줄 하나다.**
     *
     * `v_content_map_capability` 는 `LEFT JOIN capability_evidence ce ON ce.capability_id = c.id`
     * 로 `evidence` 를 붙이고, **그 조인을 접는 장치가 뷰 안에 없다.** 오늘은
     * `capability_evidence.capability_id` 가 PK 라 1:1 이고 실측 465건도 전부 정확히 1건이지만, 그
     * 가정을 여기 두지 않는다 — `evidence` 가 기능당 여러 행이 되는 날 같은 기능이 두 줄 서고,
     * `steps.size == total - notAStep` 등식이 **조용히** 깨진다. 컴파일 오류가 아니라 틀린 화면으로
     * 나타나는 종류의 고장이다.
     *
     * 접기 전에 다시 정렬하는 것이 요점이다. 뷰 질의의 `ORDER BY` 는 `(scene_name, capability_id)` 라
     * 같은 기능을 든 행들 사이의 순서가 정의되지 않고, 그대로 [distinctBy] 하면 **어느 `evidence` 가
     * 남는지가 우연**이 된다. `(id, entryId, branchOffset)` 로 다시 세워 그 선택을 코드가 적어 둔다.
     *
     * 그 정렬은 씬 안의 줄 순서도 정한다 — `capability.id` 오름차순, 즉 적재 순서이자 문서 순서다.
     * 화면이 정렬을 다시 하지 않아도 되게 서버가 순서를 정하는 것은 씬을 이름순으로 내는 것과 같은
     * 판단이고, 같은 문서를 다시 적재해도 흔들리지 않는다.
     */
    private suspend fun stepsByScene(contentMapId: Long): Map<Long, List<SceneStepResponse>> =
        contentMaps.findCapabilityRows(contentMapId).toList()
            .sortedWith(
                compareBy(
                    { it.capabilityId },
                    { it.entryId.orEmpty() },
                    { it.branchOffset ?: Int.MIN_VALUE },
                )
            )
            .distinctBy { it.capabilityId }
            .groupBy({ it.sceneId }, ::stepOf)

    /**
     * 뷰 한 줄을 단계 한 줄로.
     *
     * `condition_tree` 가 null 이면 [SceneStepResponse.given] 도 **null 이다.** `{kind:"always"}` 로
     * 채우지 않는다 — 그 칸이 비는 것은 `evidence` 출신이 아닌 기능(observed · inferred · human)이라
     * `capability_evidence` 행 자체가 없다는 뜻이고, "조건이 없다"와 "`evidence` 가 없어 조건을 모른다"는
     * 다른 말이다. 뒤쪽을 앞쪽으로 적으면 TC 가 없는 `evidence` 를 지어낸다.
     *
     * `{}` 는 다르다. `evidence` 가 "조건 없음"이라고 말한 것이라 파서가 `always` 로 읽고 그대로 나간다.
     *
     * `readTree` 가 실패할 수 없다 — 입력이 `jsonb` 컬럼이라 DB 가 이미 JSON 임을 보증한다. 파서도
     * 이 경로에서는 던지지 않는다(모르는 `kind` 는 `unknown` 으로 남는다).
     */
    private fun stepOf(row: ContentMapCapabilityRow) = SceneStepResponse(
        id = row.capabilityId,
        summary = row.summary,
        status = row.status,
        interaction = row.interaction,
        inputKey = row.inputKey,
        controlLabel = row.controlLabel,
        controlPath = row.controlPath,
        givenText = row.givenText,
        given = row.conditionTree
            ?.let { ConditionNodeResponse.of(evidence.parseCondition(objectMapper.readTree(it.asString()))) },
    )

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
