package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * 화면 판정 목록을 두고 orchestration 과 agent 가 주고받는 프레임 다섯 (ARTEL-655 · ARTEL-668).
 *
 * **이 파일이 계약이다.** agent-server 쪽 구현(ARTEL-656 · ARTEL-657)이 읽을 산문 설명은
 * `docs/screen-selector-frames.md` 에 있고, 이 파일은 그 문서가 말하는 것의 실제 모양이다. 둘이
 * 갈리면 이 파일이 맞다.
 *
 * ```
 * ORCHE_TO_AGENT  SCREEN_SELECTOR_PROPOSAL   목록에 없는 selector 를 물어본다
 * AGENT_TO_ORCHE  SCREEN_SELECTOR_VERDICT    그 제안에 대한 답 (correlationId = 제안의 messageId)
 * AGENT_TO_ORCHE  SCREEN_SELECTOR_RULE       QA agent 의 tool 이 목록을 고친다
 * ORCHE_TO_AGENT  SCREEN_SELECTOR_RESULT     위 둘의 답 — 받아들인 항목과 거절한 항목
 * ORCHE_TO_AGENT  SCREEN_SETTLED             관측이 확정한 화면을 알린다. 답이 없다
 * ```
 *
 * ## 다섯 번째가 여기 있는 이유
 *
 * [SETTLED] 는 목록을 묻지도 고치지도 않는데 같은 파일에 둔다. 그 프레임이 싣는
 * `discriminator` 가 **이 목록이 만들어 낸 결과 그 자체**이기 때문이다 — 목록이 얇아 화면이
 * 뭉쳤다는 것을 agent 가 보는 자리가 거기고, 그것을 보고 [RULE] 을 보내는 것이 ARTEL-657 이다.
 * 계약을 두 파일로 가르면 그 왕복의 절반씩이 서로 다른 문서에 앉는다.
 *
 * ## 답은 목록 항목이지 화면 판정이 아니다
 *
 * `VERDICT` 도 `RULE` 도 [ScreenSelectorEntryFrame] 의 배열로 답한다. "이 화면은 3번과 같다" 로
 * 답하는 형식을 쓰지 않는 이유는 그것이 **카드를 서른 번째 뽑을 때 또 물어야 하는 답**이기
 * 때문이다. 무엇이 화면을 가르고 무엇이 안 가르는지로 답해야 한 번 묻고 끝난다.
 *
 * ## 항목에 정규식을 싣지 않는다
 *
 * [ScreenSelectorEntryFrame.pattern] 은 정확 문자열이다. 이 항목은 `discriminator` 를 만드는
 * Kotlin(`ScreenSelectorWhitelist`)과 화면을 합치는 SQL(`screen_defining_selector`) 양쪽에서
 * 평가되는데, `java.util.regex` 와 POSIX ARE 는 다르다. 한쪽에서만 맞는 항목이 하나 생기면 같은
 * 화면이 두 `discriminator` 로 갈리고, `uk_screen_discriminator` 가 막으려던 분열이 목록 쪽에서
 * 다시 열린다.
 *
 * 두 번째 이유는 이 항목을 LLM 이 쓴다는 것이다. **잘못된 정확 문자열은 아무것에도 안 맞고
 * 끝나지만, 잘못된 정규식은 전부 맞고 그것이 조용하다.** 경로 아래 전부를 가리키고 싶으면
 * `subtree` 를 쓴다 — 마디 경계로만 맞으므로 `Zone1` 이 `SomeZone1Extra` 에 걸리지 않는다.
 */
object ScreenSelectorFrames {
    /** 목록에 없는 selector 를 물어보는 제안. `ORCHE_TO_AGENT`. */
    const val PROPOSAL = "SCREEN_SELECTOR_PROPOSAL"

    /** 제안에 대한 답. `AGENT_TO_ORCHE`. `correlationId` 가 제안 프레임의 `messageId` 다. */
    const val VERDICT = "SCREEN_SELECTOR_VERDICT"

    /** QA agent 의 tool 이 목록을 고친다. `AGENT_TO_ORCHE`. 제안 없이 스스로 보낸다. */
    const val RULE = "SCREEN_SELECTOR_RULE"

    /**
     * [VERDICT] 와 [RULE] 의 답. `ORCHE_TO_AGENT`.
     *
     * 둘을 한 타입으로 답한다. `KNOWLEDGE_WRITE_RESULT` 가 다섯 쓰기에 하나로 답하는 것과 같은
     * 판단이다(ARTEL-331) — 둘은 "목록 항목을 쓴다" 는 한 가족이고 응답의 모양이 같다. 무엇의
     * 답인지는 payload 의 `type` 이 말한다.
     */
    const val RESULT = "SCREEN_SELECTOR_RESULT"

    /**
     * 관측이 화면을 확정했다는 통보. `ORCHE_TO_AGENT`. **답이 없다** (ARTEL-668).
     *
     * ## [PROPOSAL] 과 겸하지 않는다
     *
     * 겸하게 두었던 것이 이 프레임이 생긴 이유다. [PROPOSAL] 은 `(scene, selector)` 마다 평생 한
     * 번만 나가고 `uk_screen_selector_proposal` 이 그것을 영구히 보장한다. 그래서 **이미 한 번
     * 플레이한 빌드에서는 제안이 한 장도 안 나가고**, 거기 곁들여 실려 가던 화면 판정도 함께
     * 사라진다. agent 는 런 내내 지도가 자기를 어느 화면이라고 부르는지 못 보고, 목록을 고치는
     * tool 둘(ARTEL-657)은 부를 계기를 잃는다.
     *
     * 질문과 사실은 나가는 조건이 다르다. 질문은 물어볼 것이 새로 생겼을 때 한 번, 사실은 그
     * 사실이 달라질 때마다다. 한 타입에 둘을 실으면 둘 중 드문 쪽의 조건이 이긴다.
     *
     * ## 화면이 바뀔 때만 나간다
     *
     * 실측 런의 `pulse` 가 14489 개이고 그 런이 남긴 화면은 3 행이다. `pulse` 마다 보내면 같은
     * 말을 만 번 반복하면서 agent 의 컨텍스트를 채운다.
     */
    const val SETTLED = "SCREEN_SETTLED"

    val INBOUND = setOf(VERDICT, RULE)
}

/** 제안이 가리키는 씬. 이름은 사람이 읽고 id 는 답이 되돌아올 자리를 정한다. */
data class ScreenSelectorSceneRef(
    @JsonProperty("scene_id") val sceneId: String,
    val name: String,
)

/**
 * 제안에 실리는 화면 하나.
 *
 * id 를 문자열로 싣는다. 64비트 id 가 JSON 숫자로 나가면 자바스크립트 소비자에서 정밀도가 깎인다 —
 * 다른 payload 와 같은 관례다.
 */
data class ScreenSelectorScreenRef(
    @JsonProperty("screen_id") val screenId: String,
    val name: String? = null,
    val discriminator: List<ScreenDiscriminatorEntry> = emptyList(),
    /** 이 화면의 캡처. 서명된 단기 주소이고, 캡처가 없으면 null 이다. */
    @JsonProperty("capture_url") val captureUrl: String? = null,
    @JsonProperty("capture_expires_at") val captureExpiresAt: Instant? = null,
)

/**
 * 잇단 두 `pulse` 사이에 달라진 것 하나.
 *
 * [was] 가 null 이면 이 `pulse` 에서 **처음 본** 객체다. 있다가 사라지는 경우는 없다 — `pulse` 는
 * 말하지 않은 객체를 지우지 않고, 꺼진 것은 `deactive` 로 따로 실려 온다(`PulseReading`).
 */
data class ScreenSelectorChange(
    val selector: String,
    val was: Boolean? = null,
    val now: Boolean,
)

/**
 * 목록에 넣을지 물어보는 후보 하나와 그 통계.
 *
 * 통계 셋은 **답하는 쪽이 게임을 모른 채 판단할 수 있게** 하려고 싣는다. 셋 다 그 자체로는 판정
 * 규칙이 아니다 — 규칙으로 쓰려던 시도가 셋 다 반례를 냈고(V60 머리말의 표) 그래서 이 이슈가
 * 묻는 쪽으로 갔다. 여기서는 **사람이 캡처를 보고 판단할 때 곁들여 보는 숫자**의 자리다.
 *
 * @property instancesInReading 이 `pulse` 에서 같은 [path] 를 가진 객체가 몇 개인가. 여럿이면
 *   스폰되는 것일 가능성이 높다 — 실측에서 `RangedCat(Clone)` 이 셋이었다. 다만 이름이 같은 형제
 *   컨트롤(확인 버튼과 취소 버튼)도 여기 걸리므로 이것만으로 빼면 안 된다.
 * @property readingsSeenInScene 이 씬에서 이 selector 를 몇 개의 `pulse` 에서 봤나. **프로세스
 *   메모리의 값이다** — 재시작하면 0 부터 다시 센다. 통계를 위해 표를 하나 더 두지 않는 것은
 *   그 표가 플레이 길이만큼 자라기 때문이고, 그것이 ARTEL-654 가 이 방향을 버린 이유다.
 * @property distinctValuesObserved 같은 [path] 가 되는 서로 다른 selector 원문을 몇 개 봤나.
 *   `Card(Clone)[37]` 과 `Card(Clone)[38]` 이 둘이다. 1 이면 index 가 흔들리지 않는 고정 UI 다.
 * @property inWhitelist 이 후보가 지금 목록에 들어 있나. `scene-screen-cap` 제안에서만 `true` 이고,
 *   그때 답은 뺄 항목이다.
 */
data class ScreenSelectorCandidate(
    val selector: String,
    val path: String,
    val active: Boolean,
    @JsonProperty("instances_in_reading") val instancesInReading: Int,
    @JsonProperty("readings_seen_in_scene") val readingsSeenInScene: Int,
    @JsonProperty("distinct_values_observed") val distinctValuesObserved: Int,
    @JsonProperty("in_whitelist") val inWhitelist: Boolean,
)

/**
 * `SCREEN_SELECTOR_PROPOSAL` 의 payload.
 *
 * 답하는 쪽은 **이 payload 만 보고** 답해야 한다(ARTEL-656). 특정 게임의 관례를 프롬프트에 적으면
 * 그 게임에서만 맞는 판정기가 되므로, 판단에 필요한 것이 전부 여기 실려야 한다 — 이전 화면, 잇단 두
 * `pulse` 의 차이, 그 시점의 캡처, 후보와 그 통계.
 *
 * @property previousScreen 지금 화면 **직전**에 굳었던 화면. 없으면 null 이다 — 런의 첫 화면이거나
 *   서버가 재시작해 `fold` 상태를 잃은 경우다.
 * @property currentScreen 지도가 지금 여기라고 말하는 화면. 후보가 이 화면을 가르지 못한 채
 *   나타났다는 것이 이 제안의 내용이다.
 */
data class ScreenSelectorProposalPayload(
    val reason: String,
    val scene: ScreenSelectorSceneRef,
    @JsonProperty("previous_screen") val previousScreen: ScreenSelectorScreenRef? = null,
    @JsonProperty("current_screen") val currentScreen: ScreenSelectorScreenRef? = null,
    val changes: List<ScreenSelectorChange> = emptyList(),
    val candidates: List<ScreenSelectorCandidate> = emptyList(),
)

/**
 * 목록 항목 하나. `VERDICT` 와 `RULE` 이 이것의 배열로 답하고, 받아들여지면
 * `scene_screen_selector` 한 행이 된다.
 *
 * @property match `selector`(원문 하나) · `path`(형제 index 를 지운 경로) · `subtree`(그 경로와 그
 *   아래 전부, 마디 경계). 셋뿐이다 — 정규식은 없다(파일 머리말).
 * @property pattern 맞대 볼 **정확 문자열**.
 * @property screenDefining 이 대상이 화면을 식별하는가. `false` 는 명시적 제외이고, 이 값이 기존
 *   화면을 합치는 유일한 방향이다.
 * @property reason 왜 그렇게 판단했나. 필수다 — 사유 없는 항목은 나중에 사람이 되짚을 수 없고,
 *   되짚을 수 없는 항목은 지울지 말지도 판단할 수 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenSelectorEntryFrame(
    val match: String? = null,
    val pattern: String? = null,
    @JsonProperty("screen_defining") val screenDefining: Boolean? = null,
    val reason: String? = null,
)

/**
 * `SCREEN_SELECTOR_VERDICT` 의 payload. 제안 하나에 대한 답이다.
 *
 * [entries] 가 비어도 정상이다 — "물어본 것 중 화면을 가르는 것이 없다" 가 그 답이고, 기본값이
 * 무시라 그 경우 아무것도 저장할 것이 없다. 모델이 형식을 어겼을 때도 지어내지 말고 빈 배열로
 * 답한다(ARTEL-656).
 *
 * @property proposalId 답하는 제안의 `messageId`. 봉투의 `correlationId` 와 같은 값이고, 둘 중
 *   하나만 있어도 푼다 — 구버전 agent 가 봉투 필드를 안 채우는 경우를 대비해 payload 에도 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenSelectorVerdictPayload(
    @JsonProperty("proposal_id") val proposalId: String? = null,
    val entries: List<ScreenSelectorEntryFrame> = emptyList(),
    val note: String? = null,
)

/**
 * `SCREEN_SELECTOR_RULE` 의 payload. QA agent 의 tool 둘이 이 하나로 온다 (ARTEL-657).
 *
 * "이 selector 를 화면 판정에 쓴다" 는 `screen_defining=true`, "무시한다" 는 `false` 다. tool 이
 * 둘이어도 프레임을 둘로 가르지 않는 이유는 저장되는 것이 같은 표의 같은 행 모양이기 때문이다 —
 * 가르면 다음에 세 번째 방향이 생겼을 때 프레임이 셋이 된다.
 *
 * @property scene 고칠 씬의 이름. **agent 가 지금 서 있는 씬이어야 한다.** 목록은 씬 단위이고,
 *   다른 씬은 그 씬에 서서 본 것이 아니므로 근거가 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenSelectorRulePayload(
    val scene: String? = null,
    val entries: List<ScreenSelectorEntryFrame> = emptyList(),
)

/** 받아들인 항목 하나. 저장된 그대로 되돌려 준다. */
data class ScreenSelectorAcceptedEntry(
    val match: String,
    val pattern: String,
    @JsonProperty("screen_defining") val screenDefining: Boolean,
)

/** 거절한 항목 하나와 그 사유. 사유 없이 거절하면 부른 쪽이 같은 실수를 반복한다. */
data class ScreenSelectorRejectedEntry(
    val match: String? = null,
    val pattern: String? = null,
    val reason: String,
)

/**
 * `SCREEN_SELECTOR_RESULT` 의 payload.
 *
 * @property type 무엇의 답인가 — `SCREEN_SELECTOR_VERDICT` 또는 `SCREEN_SELECTOR_RULE`.
 * @property foldedScreens 이 답 때문에 사라진 화면 수. 0 이 보통이다 — 항목을 **넣는** 답은 과거
 *   화면을 가르지 않고(그 값이 애초에 기록에 없다) 다음 관측부터 갈린다. 0 이 아니면 빼는 방향의
 *   답이 기존 행을 합쳤다는 뜻이다.
 */
data class ScreenSelectorResultPayload(
    val type: String,
    @JsonProperty("scene_id") val sceneId: String? = null,
    val accepted: List<ScreenSelectorAcceptedEntry> = emptyList(),
    val rejected: List<ScreenSelectorRejectedEntry> = emptyList(),
    @JsonProperty("folded_screens") val foldedScreens: Int = 0,
)

/**
 * `SCREEN_SETTLED` 의 payload (ARTEL-668).
 *
 * 필드 철자를 [ScreenSelectorProposalPayload] 와 **일부러 같게** 두었다. agent-server 는
 * 제안에서 이미 `scene` · `previous_screen` · `current_screen` 셋만 읽어 화면 판정을 그리고
 * 있으므로(`app/qa/screen.py` 의 `ScreenMap.apply`), 같은 철자면 저쪽은 타입 하나를 라우터에
 * 등록하는 것으로 끝난다. 새 이름을 지으면 같은 값을 두 번 읽는 코드가 저쪽에 한 벌 더 생긴다.
 *
 * @property currentScreen 방금 굳은 화면. **null 이 될 수 없다** — 굳지 않았으면 이 프레임이
 *   나가지 않는다. 제안 쪽이 nullable 인 것은 아직 아무 화면도 안 굳은 시점에도 질문이 나가기
 *   때문이고, 여기는 그 시점이 없다.
 * @property previousScreen 이 화면 직전에 굳었던 화면. 런의 첫 화면이거나 서버가 재시작해
 *   `fold` 상태를 잃었으면 null 이다.
 */
data class ScreenSettledPayload(
    val scene: ScreenSelectorSceneRef,
    @JsonProperty("previous_screen") val previousScreen: ScreenSelectorScreenRef? = null,
    @JsonProperty("current_screen") val currentScreen: ScreenSelectorScreenRef,
)
