package kr.artel.orchestration.contentmap.dto

import com.fasterxml.jackson.databind.JsonNode
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
 *
 * **그래프가 둘이고, 답하는 질문이 다르다.**
 *
 * | | 무엇 | 질문 |
 * |---|---|---|
 * | [edges] | 씬 전이 | 이 게임의 구조가 어떻게 생겼나. 아직 안 가본 곳도 나온다 |
 * | [screenTransitions] | 화면 전이 | 실제로 어떻게 흘렀나. 관측된 것만 나온다 |
 *
 * 화면 전이가 씬 안에 접히지 않고 최상위에 서는 이유: [ContentMapScreenTransitionResponse.crossesScene]
 * 인 전이는 두 씬에 걸쳐 있어 어느 씬에 넣어도 반쪽이 된다.
 */
@Schema(description = "씬 명세(content map) 조회 결과")
data class ContentMapResponse(
    @Schema(description = "지도 루트. null 이면 이 빌드에 등록된 `evidence` 문서가 없다")
    val contentMap: ContentMapSummaryResponse?,
    @Schema(description = "씬 목록. 이름 오름차순")
    val scenes: List<ContentMapSceneResponse>,
    @Schema(description = "씬 전이")
    val edges: List<ContentMapEdgeResponse>,
    @Schema(description = "화면 전이. QA 런이 관측한 것만 있다")
    val screenTransitions: List<ContentMapScreenTransitionResponse> = emptyList(),
    @Schema(description = "명세가 못 된 사유의 분포. QA 결함이 아니라 개발 우선순위 신호다")
    val gaps: List<SpecGapCountResponse>,
    @Schema(description = "`evidence` 출신 기능 중 실행으로 확인된 비율")
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
 * [capture] · [schemaVersion] · [evidenceDigest] 는 근거 문서가 말해 주는 것이라, 문서 없이 관측만으로
 * 선 지도에서는 null 이다(ARTEL-642). **필드를 지우지는 않는다** — artel-home 의 `contentMapApi.ts` 가
 * [capture] 를 읽고, null 은 "알 수 없음"으로 그려지지만 필드가 없으면 요약 패널이 빈칸이 된다.
 *
 * 셋이 비었다는 사실로 [rootedBy] 를 유도하지 않는다. 근거가 아직 안 온 지도와 근거가 왔는데
 * 헤더가 빈 지도를 그 방법으로는 가릴 수 없어서 V63 5절이 컬럼을 따로 둔 것이고, 같은 이유로
 * 응답에서도 따로 낸다.
 *
 * @property ingestedAt 이 지도의 문서 중 **가장 나중에 앉은 시각**. null 이면 등록만 되고 아직
 *   아무 문서도 앉지 않았다는 뜻이다. `content_map` 행이 아니라 문서에서 유도하는 것은 적재기가
 *   `content_map` 을 건드리지 않기 때문이다 — 지문·유니티 버전·약속은 등록 경로가 소유한다.
 */
@Schema(description = "지도 루트")
data class ContentMapSummaryResponse(
    val id: Long,
    @Schema(description = "이 지도를 세운 경로. evidence 는 근거 문서 등록이, observation 은 QA 런의 관측이 세웠다")
    val rootedBy: String,
    @Schema(description = "editor · editor-play · player 중 하나. 근거 문서가 아직 없으면 null")
    val capture: String?,
    @Schema(description = "근거 문서의 세대. 근거 문서가 아직 없으면 null")
    val schemaVersion: Int?,
    @Schema(description = "구워진 `evidence` 전체의 지문. 값이 달라지면 코드가 바뀐 것이다. 근거 문서가 아직 없으면 null")
    val evidenceDigest: String?,
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
 * @property origin 이 씬을 어디서 알아냈나. `evidence` 는 정적 분석이 설명한 씬이고 `observed` 는
 *   **QA 런이 서 보기만 한 씬**이다(ARTEL-689). 문서가 없는 씬은 더 약한 주장이라, 둘을 같아
 *   보이게 그리면 지도의 어느 부분이 어디서 왔는지가 화면에서 사라진다. `observed` 씬은
 *   [thumbnail] 과 [capabilities] 가 비어 있는 것이 정상이다 — 그 값들은 근거 walk 가 만든다
 */
@Schema(description = "씬 하나")
data class ContentMapSceneResponse(
    val id: Long,
    val name: String,
    @Schema(description = "순회했나. false 면 기능이 비어 있는 것이 정상이다")
    val walked: Boolean,
    @Schema(description = "evidence · observed. observed 는 QA 런이 서 보기만 한 씬이라 기능이 비는 것이 정상이다")
    val origin: String,
    val capabilities: SceneCapabilityCountResponse,
    @Schema(description = "이 씬의 조작 단계. not-a-step 은 빠진다")
    val steps: List<SceneStepResponse> = emptyList(),
    @Schema(description = "이 씬의 대표 이미지. null 이면 `evidence` 가 캡처를 아예 신고하지 않았다")
    val thumbnail: SceneThumbnailResponse? = null,
    @Schema(description = "이 씬에서 관측된 화면. QA 런 전에는 비고 그것이 정상이다")
    val screens: List<ContentMapScreenResponse> = emptyList(),
    @Schema(description = "capabilities 가 센 그 행들. 개수의 합이 capabilities.total 과 같다")
    val capabilityList: List<SceneCapabilityResponse> = emptyList(),
)

/**
 * 이 씬에서 관측된 화면 하나.
 *
 * **씬 하나에 화면이 여럿일 수 있다** — 오버레이·팝업·상태 분기. 정적 분석은 화면을 모르므로 QA 런
 * 전에는 [ContentMapSceneResponse.screens] 가 비고, **그것이 정상이다.** 화면이 이 빈 목록을 결함으로
 * 읽으면 안 된다. 씬을 그리다 화면이 생기는 것이지 화면이 없어 씬이 덜 그려지는 것이 아니다.
 *
 * @property name 표시용이고 **조인 키가 아니다.** 기계는 [discriminator] 로 판정하고 이름은 LLM 이
 *   짓는다. null 이면 아직 아무도 이름을 붙이지 않은 것이다
 * @property discriminator 이 화면임을 판정하는 pulse 관측 조건.
 *   `[{"selector":"Canvas[2]/continue[2]","active":true}]`. 서버는 이 값을 **읽지 않고 그대로
 *   옮긴다** — 판정은 런타임이 하고 화면은 사람에게 보여 줄 뿐이라, 모양을 여기서 못 박으면 관측
 *   쪽이 조건 어휘를 늘리는 날 조회가 먼저 깨진다
 * @property observedCount 이 화면을 몇 번 지나갔나. 0 은 화면 행이 있는데 관측이 없는 것이라 정상이
 *   아니지만, 조회는 그 판단을 하지 않고 그대로 옮긴다
 * @property firstSeenQaRunId 이 화면을 처음 본 런. 런이 지워지면 null 이 된다(`ON DELETE SET NULL`)
 * @property capabilities 이 `screen` 에 실제로 묶인 `capability`.
 *   **[ContentMapSceneResponse.capabilityList] 의 부분집합이고, 비어 있으면 비어 있는 채로 나간다** —
 *   `scene` 의 목록으로 대신 채우지 않는다. 빈 목록은 "이 `screen` 에서 아직 아무것도 확인 안 됐다"
 *   이고 `scene` 의 목록은 "이 `scene` 어딘가에서 할 수 있다"라, 둘을 합치면 인스펙터가 그 `screen`
 *   의 것이 아닌 목록을 보여 준다(ARTEL-658)
 */
@Schema(description = "씬 안의 화면 하나")
data class ContentMapScreenResponse(
    val id: Long,
    @Schema(description = "이 화면이 속한 씬. 화면 전이가 화면 id 로만 오므로 되짚을 자리가 필요하다")
    val sceneId: Long,
    @Schema(description = "표시용. 조인 키가 아니다")
    val name: String?,
    @Schema(description = "이 화면임을 판정하는 pulse 관측 조건. 서버는 읽지 않고 그대로 옮긴다")
    val discriminator: JsonNode,
    @Schema(description = "이 화면을 몇 번 지나갔나")
    val observedCount: Int,
    @Schema(description = "이 화면을 처음 본 QA 런")
    val firstSeenQaRunId: Long?,
    @Schema(description = "이 화면의 캡처. null 이면 아직 못 찍었다")
    val image: ScreenImageResponse? = null,
    @Schema(description = "이 screen 에 묶인 capability. scene 의 capabilityList 로 대신 채우지 않는다")
    val capabilities: List<ScreenCapabilityResponse> = emptyList(),
)

/**
 * `screen` 하나에 묶인 `capability` 한 줄 (ARTEL-658).
 *
 * **`scene` 의 [SceneCapabilityResponse] 와 답하는 질문이 다르다.** 저쪽은 "이 `scene` 어딘가에서
 * 무엇을 할 수 있나"이고 이쪽은 "이 `screen` 에서 실제로 무엇이 되더라"이다. 정적 `evidence` 가 아는
 * 것은 "이 타입이 이 `scene` 에 놓였다"까지고, 어느 `screen` 상태에서 눌리는지는 런타임만 안다.
 *
 * `screen_transition` 으로 유도할 수도 없다. 그 표의 `capability_id` 는 무엇이 전이를 일으켰는지를
 * 정직하게 귀속할 방법이 생기기 전까지 비어 있어(ARTEL-450), 전이에서 뽑으면 모든 `screen` 이 빈
 * 목록이 된다.
 *
 * 판정 세 축은 담지 않는다. `scene` 의 [SceneCapabilityResponse] 에 같은 [id] 로 이미 나가 있고,
 * `screen` 이 수십 개인 `scene` 에서 같은 값을 다시 실으면 그 비용이 `screen` 수만큼 곱해진다. 두
 * 목록은 `capability.id` 로 이어진다.
 *
 * @property observedCount 이 `screen` 에서 이 `capability` 를 몇 번 봤나
 * @property firedCount 그중 실제로 무언가 변한 횟수. [observedCount] 와의 차이가 결함 신호다 —
 *   눌렀는데 아무것도 안 변한 횟수
 */
@Schema(description = "screen 에 묶인 capability 하나")
data class ScreenCapabilityResponse(
    @Schema(description = "capability.id. scene 의 capabilityList 와 steps 의 같은 id 가 같은 행이다")
    val id: Long,
    val summary: String,
    @Schema(description = "runnable · needs-probe · unreachable-precondition · not-a-step")
    val status: String,
    @Schema(description = "evidence · observed · inferred · human. 어디서 알아냈나")
    val origin: String,
    @Schema(description = "unverified · confirmed · contradicted. 실행으로 확인됐나")
    val verification: String,
    @Schema(description = "이 screen 에서 이 capability 를 몇 번 봤나")
    val observedCount: Int,
    @Schema(description = "그중 실제로 무언가 변한 횟수. observedCount 와의 차이가 결함 신호다")
    val firedCount: Int,
) {
    companion object {
        fun of(row: ScreenCapabilityRow) = ScreenCapabilityResponse(
            id = row.capabilityId,
            summary = row.summary,
            status = row.status,
            origin = row.origin,
            verification = row.verification,
            observedCount = row.observedCount,
            firedCount = row.firedCount,
        )
    }
}

/**
 * 화면 캡처의 주소. 씬 대표 이미지와 **같은 서명 경로**를 쓴다(`DocumentStorage.presignDownload`).
 *
 * [SceneThumbnailResponse] 와 달리 `state` `discriminator` 가 없다. `screen` 표에는 실패 코드 칸이 없어
 * (`image_failure_code` 는 `scene` 에만 있다) 가를 두 상태가 없기 때문이다 — 캡처가 있으면 이
 * 객체가 있고, 없으면 [ContentMapScreenResponse.image] 가 통째로 null 이다. 없는 상태를 흉내 내는
 * 칸을 만들면 화면이 영원히 오지 않는 값을 분기한다.
 *
 * @property capturedAt 이 캡처를 찍은 시각. 화면이 지금 모양과 얼마나 떨어진 그림인지를 말한다
 */
@Schema(description = "화면 캡처의 서명된 주소")
data class ScreenImageResponse(
    @Schema(description = "서명된 단기 주소")
    val url: String,
    val expiresAt: Instant,
    @Schema(description = "찍은 시각. null 이면 관측이 시각을 남기지 않았다")
    val capturedAt: Instant?,
)

/**
 * 화면 전이 하나. **관측만 있다.**
 *
 * 정적으로 만들지 않는다 — 추측을 넣으면 "실제로 어떻게 흘렀나"가 오염된다. 씬 전이
 * ([ContentMapEdgeResponse])로 대신할 수도 없다: 팝업이 열리는 것처럼 씬 안에서만 일어나는 전이가
 * 있고, 그런 전이는 씬 그래프에 자리가 없다.
 *
 * @property capabilityId null 이면 **자동 전이**다 — 타이머·로딩 완료처럼 TC 가 지시할 수 없는 것.
 *   기능이 재적재로 지워져도 null 이 된다(`ON DELETE SET NULL`). 어느 쪽이든 "갔다는 사실"은 남는다
 * @property kind `action` · `state` · `auto` 중 하나
 * @property crossesScene 씬 경계를 넘었나. false 면 같은 씬 안의 상태 변화다. 이 칸이 있어야 화면이
 *   중첩 다이어그램에서 씬 컨테이너 안의 선과 밖의 선을 가른다
 */
@Schema(description = "화면 전이 하나")
data class ContentMapScreenTransitionResponse(
    val id: Long,
    val fromScreenId: Long,
    val toScreenId: Long,
    @Schema(description = "null 이면 자동 전이 — TC 가 지시할 수 없다")
    val capabilityId: Long?,
    @Schema(description = "무엇을 해서 넘어갔나")
    val capabilitySummary: String?,
    @Schema(description = "action · state · auto")
    val kind: String,
    @Schema(description = "씬 경계를 넘었나. false 면 같은 씬 안의 상태 변화다")
    val crossesScene: Boolean,
    @Schema(description = "이 전이를 몇 번 지나갔나")
    val observedCount: Int,
    @Schema(description = "이 전이를 처음 본 QA 런")
    val firstSeenQaRunId: Long?,
) {
    companion object {
        fun of(row: ContentMapScreenTransitionRow) = ContentMapScreenTransitionResponse(
            id = row.id,
            fromScreenId = row.fromScreenId,
            toScreenId = row.toScreenId,
            capabilityId = row.capabilityId,
            capabilitySummary = row.capabilitySummary,
            kind = row.kind,
            crossesScene = row.crossesScene,
            observedCount = row.observedCount,
            firstSeenQaRunId = row.firstSeenQaRunId,
        )
    }
}

/**
 * 이 씬의 기능 하나. **[SceneCapabilityCountResponse] 가 센 그 행이다.**
 *
 * [ContentMapSceneResponse.steps] 와 겹치되 같지 않다. 이쪽이 상위집합이다.
 *
 * | | 무엇 | 골든 문서 |
 * |---|---|---|
 * | `steps` | 조작이 있는 기능 | 51 행 |
 * | `capabilityList` | 이 씬의 기능 전부 | 491 행 |
 *
 * 차이가 `not-a-step` 이다. 그 행들은 단독 명세가 될 수 없어 단계 목록에 들어가면 안 되지만,
 * given/then 의 재료로 실재하는 행이라 인스펙터가 "그 440 이 무엇인가"를 물으면 답이 있어야 한다.
 * 그래서 `capabilityList.size == capabilities.total` 이고, `steps.size == total - notAStep` 이다.
 * 두 등식이 함께 성립하지 않으면 셋 중 하나가 거짓말을 시작한 것이다.
 *
 * 칸 이름을 `capabilities` 로 하지 못한 것은 그 이름을 카운트가 이미 쓰고 있어서다. 이름을 뺏으면
 * 추가만 하는 변경이 아니게 된다.
 *
 * 컨트롤 정보(`controlLabel` · `controlPath` · `inputKey`)와 조건 트리는 여기 없다. 그 칸이 찬 행은
 * 조작이 있는 행이고 그것은 [SceneStepResponse] 가 이미 든다 — 두 목록은 [id] 로 잇는다. 아홉 배
 * 큰 목록에 같은 값을 다시 실을 이유가 없다.
 *
 * @property status 세 축에서 **유도된** 값이다. 축을 함께 내는 것은 화면이 "왜 runnable 이 아닌가"를
 *   답할 수 있어야 하기 때문이다
 * @property origin `evidence` · `observed` · `inferred` · `human`. [verification] 과 다른 축이다 —
 *   이쪽은 출처이고 저쪽은 실행 확인이다
 * @property scenePresence 이 행이 왜 이 `scene` 에 있나(ARTEL-460). `persistent-unconfirmed` 는
 *   `scene` 을 넘어 살아남는 오브젝트가 여기 있다는 사실뿐이라, 여기서 그 기능이 되는지는 아직
 *   아무도 안 봤다. 화면이 이 값을 안 그리면 `TurnBattleScene` 의 목록에서 그 줄이 근거가 이
 *   `scene` 에 놓은 줄과 똑같이 보인다
 */
@Schema(description = "씬 하나의 기능 하나")
data class SceneCapabilityResponse(
    @Schema(description = "capability.id. steps 의 같은 id 와 같은 행이다")
    val id: Long,
    val summary: String,
    @Schema(description = "runnable · needs-probe · unreachable-precondition · not-a-step")
    val status: String,
    @Schema(description = "evidence · observed · inferred · human. 어디서 알아냈나")
    val origin: String,
    @Schema(description = "unverified · confirmed · contradicted. 실행으로 확인됐나")
    val verification: String,
    @Schema(
        description = "placed · persistent-evidenced · persistent-unconfirmed. " +
            "이 행이 왜 이 scene 에 있나. persistent-unconfirmed 는 여기서 되는지 아직 아무도 안 봤다"
    )
    val scenePresence: String,
    @Schema(description = "이 조작을 실제로 할 수 있는가")
    val actionability: String,
    @Schema(description = "그 결과를 볼 수 있는가")
    val observability: String,
    @Schema(description = "이 빌드에 이 규칙이 적용되는가")
    val applicability: String,
    @Schema(description = "click · press · none 등. 프로토콜 메서드가 아니라 의도다")
    val interaction: String,
) {
    companion object {
        fun of(row: SceneCapabilityRow) = SceneCapabilityResponse(
            id = row.capabilityId,
            summary = row.summary,
            status = row.status,
            origin = row.origin,
            verification = row.verification,
            scenePresence = row.scenePresence,
            actionability = row.actionability,
            observability = row.observability,
            applicability = row.applicability,
            interaction = row.interaction,
        )
    }
}

/**
 * 씬 대표 이미지의 상태와 주소.
 *
 * **없음과 못 만듦을 가른다.** 옛 SDK 는 캡처를 아예 신고하지 않으므로 [ContentMapSceneResponse.thumbnail]
 * 자체가 null 이고, 새 SDK 가 캡처를 시도했다가 실패하면 여기 `unavailable` 과 [reason] 이 온다.
 * 화면이 "아직 안 올렸다"와 "이 씬은 못 찍는다"를 다르게 말할 수 있어야 한다.
 */
data class SceneThumbnailResponse(
    @Schema(description = "available · unavailable")
    val state: String,
    @Schema(description = "서명된 단기 주소. state 가 available 일 때만 있다")
    val url: String? = null,
    val expiresAt: Instant? = null,
    val width: Int? = null,
    val height: Int? = null,
    @Schema(description = "못 만든 이유. SDK 가 준 failureCode 를 그대로 옮긴다")
    val reason: String? = null,
)

/**
 * 이 씬에서 **할 수 있는 일 하나.** 개수가 답하지 못하는 "그게 무엇인가"를 답하는 줄이다.
 *
 * `not-a-step` 은 여기 오지 않는다. 조작이 없어 단독 명세가 될 수 없는 행이고, 단계가 아닌 것을
 * 단계 목록에 넣으면 화면이 누를 수 없는 것을 누르라고 그린다. 골든 문서에서 기능 491행 중 440행이
 * 거기라, 실으면 응답이 아홉 배가 되기도 한다. 그 수는 [SceneCapabilityCountResponse.notAStep] 이
 * 이미 답한다.
 *
 * 그래서 **`steps.size` 는 `capabilities.total - capabilities.notAStep` 과 같다.** 두 칸이 같은 표를
 * 본다는 뜻이고, 어긋나면 목록이나 카운트 한쪽이 거짓말을 시작한 것이다.
 *
 * 효과(`then`)는 여기 없다. 기능 하나에 여러 개라 접으면 행이 곱해진다 —
 * `v_content_map_capability` 가 효과를 빼는 것과 같은 판단이다.
 *
 * @property id `capability.id`. 재적재를 넘어 기억해 둘 값은 `capability_key` 쪽이고, 이것은
 *   표시·조인용이다
 * @property status `runnable` · `needs-probe` · `unreachable-precondition` 중 하나.
 *   `needs-probe` 는 **실패가 아니다** — 조작은 지시할 수 있고 기대 결과만 모르는 것이라, 1회차
 *   QA 런이 관측을 기록하면 2회차부터 그 관측이 기대 결과가 된다
 * @property interaction `click` · `press` · `drag` · `none` 등. 프로토콜 메서드가 아니라 의도다
 * @property givenText 조건을 한 줄로 옮긴 사람용 글. **오늘은 전부 null 이다**(ARTEL-447 미완).
 *   화면은 `givenText ?? given` 으로 고른다
 * @property given 정규화된 조건 트리. **`givenText` 가 빌 때 두 줄을 가르는 유일한 값이다.**
 *   null 이면 `evidence` 출신이 아니라 조건을 아예 모르는 것이고, `{kind:"always"}` 와 다른 말이다
 */
@Schema(description = "씬 하나의 조작 단계")
data class SceneStepResponse(
    @Schema(description = "capability.id")
    val id: Long,
    val summary: String,
    @Schema(description = "runnable · needs-probe · unreachable-precondition")
    val status: String,
    @Schema(description = "click · press · drag · none 등")
    val interaction: String,
    @Schema(description = "press 일 때의 키 이름")
    val inputKey: String?,
    @Schema(description = "누를 수 있는 것에 쓰인 글자")
    val controlLabel: String?,
    @Schema(description = "사람이 읽는 계층 경로")
    val controlPath: String?,
    @Schema(description = "조건 한 줄. 오늘은 전부 null 이다(ARTEL-447)")
    val givenText: String?,
    @Schema(description = "정규화된 조건 트리. null 이면 `evidence` 가 없어 조건을 모른다")
    val given: ConditionNodeResponse?,
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
    @Schema(description = "조작은 있는데 무엇이 달라지는지 `evidence` 가 말하지 않는다")
    val needsProbe: Long,
    @Schema(description = "조작이 없다. 단독 명세가 아니라 given/then 의 재료다")
    val notAStep: Long,
    @Schema(description = "조건은 아는데 그 상태를 만드는 절차가 `evidence` 에 없다")
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
    @Schema(description = "정규화된 전이 조건. null 이면 조건을 말하는 `evidence` 가 없다")
    val given: ConditionNodeResponse?,
    @Schema(description = "static · runtime. runtime 은 정적 분석이 놓친 전이다")
    val source: String,
    @Schema(description = "null 이면 아직 못 가본 전이 — 커버리지 구멍이다")
    val verifiedAt: Instant?,
) {
    companion object {
        fun of(row: ContentMapSceneEdgeRow, given: ConditionNodeResponse?) = ContentMapEdgeResponse(
            fromSceneId = row.fromSceneId,
            toSceneName = row.toSceneName,
            toSceneId = row.toSceneId,
            capabilityId = row.capabilityId,
            capabilitySummary = row.capabilitySummary,
            givenText = row.givenText,
            given = given,
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
 * 커버리지 지표의 분자와 분모. **`evidence` 출신 기능만 센다.**
 *
 * 분모가 우리 정적 분석 성능이고 분자가 agent 성능이라, 둘을 한 화면에 놓으면 시스템 전체가
 * 설명된다. 씬별 카운트와 총수가 다른 것이 정상이다 — 그쪽은 QA 가 관측으로 배운 기능도 센다.
 */
@Schema(description = "`evidence` 출신 기능의 실행 확인 비율")
data class VerificationResponse(
    @Schema(description = "verification 이 unverified 가 아닌 기능 수")
    val verified: Long,
    @Schema(description = "`evidence` 출신 기능 수")
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
