package kr.artel.orchestration.contentmap.dto

import io.swagger.v3.oas.annotations.media.Schema
import kr.artel.orchestration.contentmap.scan.ScanState
import kr.artel.orchestration.contentmap.scan.ScanStatus
import java.time.Instant

/**
 * 씬 명세 한 장. **화면이 이 빌드에 대해 아는 것 전부를 한 번에 받는다.**
 *
 * 섹션을 여러 엔드포인트로 쪼개지 않는 이유: 화면이 무엇을 그릴지가 [contentMap] 과
 * [ContentMapSummaryResponse.ingestedAt] **두 값의 조합**으로 정해지는데, 그 둘을 다른 호출에서
 * 받으면 두 스냅샷 사이에서 상태가 바뀌어 "등록도 안 됐는데 씬이 7개"인 화면이 나온다.
 *
 * | [contentMap] | `ingestedAt` | 뜻 |
 * |---|---|---|
 * | `null` | — | 이 빌드에 문서가 **한 번도** 등록되지 않았다 |
 * | 있음 | `null` | 등록됐는데 아직 적재되지 않았다 |
 * | 있음 | 있음 | 적재됐다. [pendingDocuments] 가 비지 않았으면 그 뒤 새 문서가 더 온 것이다 |
 *
 * 효과(`capability_effect`)는 담지 않는다. 기능 하나에 여러 개라 조인하면 행이 곱해진다 —
 * `v_content_map_capability` 가 효과를 빼는 것과 같은 판단이다.
 */
@Schema(description = "씬 명세(content map) 조회 결과")
data class ContentMapResponse(
    @Schema(description = "지도 루트. null 이면 이 빌드에 등록된 근거 문서가 없다")
    val contentMap: ContentMapSummaryResponse?,
    @Schema(description = "씬 목록. 이름 오름차순")
    val scenes: List<ContentMapSceneResponse>,
    @Schema(description = "씬 전이")
    val edges: List<ContentMapEdgeResponse>,
    @Schema(description = "명세가 못 된 사유의 분포. QA 결함이 아니라 개발 우선순위 신호다")
    val gaps: List<SpecGapCountResponse>,
    @Schema(description = "근거 출신 기능 중 실행으로 확인된 비율")
    val verification: VerificationResponse,
    @Schema(description = "등록됐지만 아직 앉지 못한 문서")
    val pendingDocuments: List<PendingDocumentResponse>,
    @Schema(description = "이 빌드에 마지막으로 시킨 원격 스캔. null 이면 이 서버가 뜬 뒤로 시킨 적이 없다")
    val lastScan: LastScanResponse? = null,
) {
    companion object {
        /**
         * 지도가 아예 없을 때의 응답.
         *
         * 404 가 아닌 이유: 빌드는 존재하고 접근도 된다. 없는 것은 **아직 아무도 올리지 않은
         * 문서**이고, 그것은 오류가 아니라 화면이 "문서를 올리세요"를 그려야 하는 정상 상태다.
         */
        val EMPTY = ContentMapResponse(
            contentMap = null,
            scenes = emptyList(),
            edges = emptyList(),
            gaps = emptyList(),
            verification = VerificationResponse(verified = 0, total = 0),
            pendingDocuments = emptyList(),
        )
    }
}

/**
 * 지도 루트.
 *
 * `backend` 와 `development` 는 싣지 않는다. 화면이 쓰지 않고, 계약에도 없다. 필요해지면 더한다 —
 * 빼는 것이 더하는 것보다 비싸다.
 *
 * @property ingestedAt 이 지도의 문서 중 **가장 나중에 앉은 시각**. null 이면 등록만 되고 아직
 *   아무 문서도 앉지 않았다는 뜻이다. `content_map` 행이 아니라 문서에서 유도하는 것은 적재기가
 *   `content_map` 을 건드리지 않기 때문이다 — 지문·유니티 버전·약속은 등록 경로가 소유한다.
 */
@Schema(description = "지도 루트")
data class ContentMapSummaryResponse(
    val id: Long,
    @Schema(description = "editor · editor-play · player 중 하나")
    val capture: String,
    val schemaVersion: Int,
    @Schema(description = "구워진 근거 전체의 지문. 같은 capture 인데 값이 다르면 코드가 바뀐 것이다")
    val evidenceDigest: String,
    val unity: String?,
    val platform: String?,
    val sdkVersion: String?,
    @Schema(description = "마지막 적재 시각. null 이면 등록만 되고 아직 앉지 않았다")
    val ingestedAt: Instant?,
)

/**
 * 씬 하나.
 *
 * @property walked 런타임이 실제로 이 씬에 서 봤나. **오늘은 항상 `false` 다** — 이 칸을 `true` 로
 *   올리는 것은 QA 런이고 그 경로가 아직 없다(적재기는 이 칸을 일부러 건드리지 않는다. 스캔이 다시
 *   돌았다고 "가 봤다"가 취소되면 안 된다). `false` 인 씬은 [capabilities] 가 전부 0 인 것이
 *   **정상**이므로, 화면이 이 칸 없이 빈 씬을 보면 결함으로 읽는다.
 */
@Schema(description = "씬 하나")
data class ContentMapSceneResponse(
    val id: Long,
    val name: String,
    @Schema(description = "순회했나. false 면 기능이 비어 있는 것이 정상이다")
    val walked: Boolean,
    val capabilities: SceneCapabilityCountResponse,
)

/**
 * 씬 하나의 기능 상태 분포. 네 칸의 합이 [total] 이다.
 *
 * [notAStep] 이 대개 가장 크다 — 실측 문서에서 기능 491행 중 440행이 여기다. 조작이 없어 단독
 * 명세가 될 수 없는 행이며, given/then 의 재료로 쓰인다. 이것을 실패로 그리면 안 된다.
 *
 * [needsProbe] 도 실패가 아니다. 조작은 지시할 수 있고 기대 결과만 모르는 것이라, 1회차 QA 런이
 * 관측을 기록하면 2회차부터 그 관측이 기대 결과가 된다.
 */
@Schema(description = "씬 하나의 기능 상태 분포")
data class SceneCapabilityCountResponse(
    @Schema(description = "네 칸의 합")
    val total: Long,
    @Schema(description = "조작이 있고 관측 가능한 효과가 있다. 판정까지 자동")
    val runnable: Long,
    @Schema(description = "조작은 있는데 무엇이 달라지는지 근거가 말하지 않는다")
    val needsProbe: Long,
    @Schema(description = "조작이 없다. 단독 명세가 아니라 given/then 의 재료다")
    val notAStep: Long,
    @Schema(description = "조건은 아는데 그 상태를 만드는 절차가 근거에 없다")
    val unreachablePrecondition: Long,
) {
    companion object {
        /** 기능이 한 줄도 앉지 않은 씬. 순회하지 못한 씬에서는 이것이 정상이다. */
        val NONE = SceneCapabilityCountResponse(0, 0, 0, 0, 0)

        fun of(row: SceneCapabilityCountRow) = SceneCapabilityCountResponse(
            total = row.total,
            runnable = row.runnable,
            needsProbe = row.needsProbe,
            notAStep = row.notAStep,
            unreachablePrecondition = row.unreachablePrecondition,
        )
    }
}

/**
 * 씬 전이 하나.
 *
 * [capabilitySummary] 와 [givenText] 는 발행된 계약에 없는 칸이지만 더했다. 없으면 화면이 간선에
 * 붙일 글자가 [toSceneName] 뿐이라, 같은 씬으로 가는 간선 여럿이 전부 같은 이름으로 보인다.
 *
 * @property toSceneId 아직 순회하지 못한 씬이면 null 이고 [toSceneName] 만 있다
 * @property verifiedAt null 이면 아직 못 가본 전이이고, 그것이 곧 커버리지 구멍이다
 */
@Schema(description = "씬 전이 하나")
data class ContentMapEdgeResponse(
    val fromSceneId: Long,
    val toSceneName: String,
    @Schema(description = "아직 순회하지 못한 씬이면 null")
    val toSceneId: Long?,
    @Schema(description = "자동 전이거나 기능이 지워졌으면 null")
    val capabilityId: Long?,
    @Schema(description = "무엇을 해서 그 씬으로 가는가. 계약 밖의 덤이다")
    val capabilitySummary: String?,
    @Schema(description = "같은 컨트롤이 조건으로 갈릴 때 둘을 가르는 조건. 계약 밖의 덤이다")
    val givenText: String?,
    @Schema(description = "static · runtime. runtime 은 정적 분석이 놓친 전이다")
    val source: String,
    @Schema(description = "null 이면 아직 못 가본 전이 — 커버리지 구멍이다")
    val verifiedAt: Instant?,
) {
    companion object {
        fun of(row: ContentMapSceneEdgeRow) = ContentMapEdgeResponse(
            fromSceneId = row.fromSceneId,
            toSceneName = row.toSceneName,
            toSceneId = row.toSceneId,
            capabilityId = row.capabilityId,
            capabilitySummary = row.capabilitySummary,
            givenText = row.givenText,
            source = row.source,
            verifiedAt = row.verifiedAt,
        )
    }
}

/**
 * 명세가 못 된 사유 하나와 그 수.
 *
 * **QA 결함이 아니라 개발 우선순위 신호다.** `then-missing` 이 많으면 수집기(SDK)를 고칠 차례이고,
 * `given-subject-unknown` 이 많으면 조건 분석기의 주어 추적이 약한 것이다.
 *
 * [reason] 을 열거형으로 좁히지 않는 것은 `v_spec_gap` 이 사유를 늘릴 때 이 DTO 가 먼저 깨지지
 * 않게 하려는 것이다 — 모르는 값을 만난 화면은 그대로 그리기만 하면 된다.
 */
@Schema(description = "명세가 못 된 사유별 집계")
data class SpecGapCountResponse(
    val reason: String,
    val count: Int,
)

/**
 * 커버리지 지표의 분자와 분모. **근거 출신 기능만 센다.**
 *
 * 분모가 우리 정적 분석 성능이고 분자가 agent 성능이라, 둘을 한 화면에 놓으면 시스템 전체가
 * 설명된다. 씬별 카운트와 총수가 다른 것이 정상이다 — 그쪽은 QA 가 관측으로 배운 기능도 센다.
 */
@Schema(description = "근거 출신 기능의 실행 확인 비율")
data class VerificationResponse(
    @Schema(description = "verification 이 unverified 가 아닌 기능 수")
    val verified: Long,
    @Schema(description = "근거 출신 기능 수")
    val total: Long,
)

/**
 * 등록됐지만 아직 앉지 못한 문서 하나.
 *
 * [ingestFailedAt] 이 찍혀 있으면 **한 번 시도했다 깨진 것**이고, 비어 있으면 아직 아무도 시도하지
 * 않은 것이다. 둘을 가르지 않으면 화면이 "적재 버튼을 누르세요"와 "이 문서는 눌러도 깨집니다"를
 * 같은 말로 그린다.
 *
 * @property ingestError 사람에게 보여 줄 사유 한 줄. 내부 예외 원문은 로그에만 남는다
 */
@Schema(description = "적재를 기다리는 문서")
data class PendingDocumentResponse(
    val documentId: Long,
    val receivedAt: Instant,
    @Schema(description = "마지막 적재 실패 시각. null 이면 아직 시도하지 않았다")
    val ingestFailedAt: Instant?,
    @Schema(description = "마지막 실패 사유 한 줄")
    val ingestError: String?,
)

/**
 * 마지막 원격 스캔의 상태. **화면이 "눌렀는데 어떻게 됐나"를 읽는 자리다.**
 *
 * 이것이 없으면 스캔 버튼은 202 를 받고 끝난다 — 요청이 갔다는 것만 알고, 게임이 스캔에 실패했는지
 * 문서가 못 앉았는지 아직 도는 중인지를 구분할 방법이 없다.
 *
 * **프로세스 메모리에 산다.** 서버가 재시작하면 null 로 돌아간다. 스캔 자체가 실패하면 SDK 가
 * 아무것도 올리지 않아 적을 문서 행이 없기 때문이고, 그 성질이 여기서는 맞다 — 이 값이 답하는
 * 질문은 "방금 누른 버튼이 어떻게 됐나"이고 재시작 뒤의 옳은 답은 "다시 눌러라"다.
 * 내구성이 필요한 것, 즉 **어떤 문서가 왜 못 앉았나**는 [PendingDocumentResponse.ingestError] 에
 * 그대로 남는다.
 */
@Schema(description = "마지막 원격 스캔의 상태")
data class LastScanResponse(
    @Schema(description = "REQUESTED · SUCCEEDED · FAILED")
    val state: ScanState,
    @Schema(description = "명령을 받은 게임 인스턴스")
    val gameInstanceId: Long,
    val gameInstanceName: String,
    val requestedAt: Instant,
    @Schema(description = "끝난 시각. REQUESTED 이면 null")
    val finishedAt: Instant?,
    @Schema(description = "이번 스캔이 앉힌 문서 수. SUCCEEDED 인데 0 이면 올라온 문서가 없었다는 뜻")
    val ingestedDocuments: Int?,
    @Schema(description = "FAILED 일 때 사람에게 보여 줄 사유")
    val error: String?,
) {
    companion object {
        fun of(status: ScanStatus) = LastScanResponse(
            state = status.state,
            gameInstanceId = status.gameInstanceId,
            gameInstanceName = status.gameInstanceName,
            requestedAt = status.requestedAt,
            finishedAt = status.finishedAt,
            ingestedDocuments = status.ingestedDocuments,
            error = status.error,
        )
    }
}

/**
 * 스캔을 시켰다는 답. **202 가 무엇을 기다리면 되는지 말하는 자리다.**
 *
 * 어느 인스턴스가 받았는지를 싣는 이유: 같은 빌드를 두 대에서 돌리는 것은 개발 중 흔하고, 그때
 * 사람은 자기가 보고 있는 게임이 명령을 받았는지 알아야 한다.
 */
@Schema(description = "원격 스캔 요청 결과")
data class StartContentMapScanResponse(
    @Schema(description = "명령을 받은 게임 인스턴스")
    val gameInstanceId: Long,
    val gameInstanceName: String,
    @Schema(description = "늘 REQUESTED 다. 조회 API 의 lastScan.state 가 여기서 움직이는 것을 본다")
    val state: ScanState,
    val requestedAt: Instant,
)
