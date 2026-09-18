package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 새로 생긴 `screen` 에 이름을 지어 달라고 묻고 답을 받는 프레임 둘 (ARTEL-910).
 *
 * **이 파일이 계약이다.** agent-server 쪽 구현(ARTEL-909)이 같은 두 타입을 반대 방향으로 읽는다.
 *
 * ```
 * ORCHE_TO_AGENT  SCREEN_NAME_REQUEST   방금 생긴 screen 의 이름을 묻는다
 * AGENT_TO_ORCHE  SCREEN_NAME           그 답 (correlationId = 요청의 messageId)
 * ```
 *
 * ## `SCREEN_SETTLED` 와 겸하지 않는다
 *
 * [ScreenSelectorFrames.SETTLED] 는 화면이 바뀔 때마다 나가고 답이 없다. 이름은 반대로 `screen`
 * 행이 처음 생길 때 한 번만 묻고 답을 기다린다. 나가는 조건도 답의 유무도 다르므로, 한 타입에
 * 실으면 둘 중 드문 쪽의 조건이 이긴다 — [ScreenSelectorFrames.SETTLED] 가 제안에서 떨어져
 * 나온 것과 같은 이유다.
 *
 * ## 값은 [ScreenSelectorScreenRef] 를 그대로 쓴다
 *
 * agent-server 는 그 모양을 이미 읽고 있다(`app/qa/screen.py`). 이름 짓기에만 쓰는 참조 타입을
 * 새로 지으면 같은 값을 두 번 읽는 코드가 저쪽에 한 벌 더 생기고, 한쪽만 `capture_url` 을
 * 놓치는 식으로 갈린다.
 */
object ScreenNameFrames {
    /** 새로 생긴 `screen` 의 이름을 묻는다. `ORCHE_TO_AGENT`. */
    const val REQUEST = "SCREEN_NAME_REQUEST"

    /** 그 답. `AGENT_TO_ORCHE`. `correlationId` 가 요청 프레임의 `messageId` 다. */
    const val NAME = "SCREEN_NAME"

    val INBOUND = setOf(NAME)
}

/**
 * `SCREEN_NAME_REQUEST` 의 payload.
 *
 * 답하는 쪽은 **이 payload 만 보고** 답한다. 그래서 판단에 필요한 것이 전부 여기 실려야 한다 —
 * 그 화면의 `screen capture`, 무엇으로 가른 화면인지(`discriminator`), 어느 씬인지.
 *
 * @property requestId 이 요청의 식별자. 답의 `correlation_id` 로 되돌아온다. 봉투의 `messageId`
 *   와 같은 값이고, 둘 중 하나만 있어도 풀린다 — 봉투 필드를 안 채우는 구현을 대비해 payload
 *   에도 둔다([ScreenSelectorVerdictPayload.proposalId] 와 같은 관례다).
 * @property screen 이름을 지을 화면. [ScreenRefs.of] 가 만든 그대로다. `name` 은 늘 null 이다 —
 *   이름이 이미 있으면 묻지 않는다.
 */
data class ScreenNameRequestPayload(
    @JsonProperty("request_id") val requestId: String,
    val screen: ScreenSelectorScreenRef,
    val scene: ScreenSelectorSceneRef,
)

/**
 * `SCREEN_NAME` 의 payload. 요청 하나에 대한 답이다.
 *
 * @property name 지은 이름. **`null` 이 정상적인 답이다** — "이 화면을 무엇이라 부를지 모르겠다"
 *   이고, 그때 `screen.name` 은 비어 있는 채로 남는다. 스키마를 채우려고 지어낸 이름보다 빈 칸이
 *   낫다(ARTEL-909).
 * @property note 왜 그 이름인지, 또는 왜 못 지었는지 한 문장. 로그에만 남고 저장하지 않는다 —
 *   적을 칸이 `screen` 에 없고, 이 답은 표시값 하나를 정하는 것이 전부다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenNamePayload(
    @JsonProperty("request_id") val requestId: String? = null,
    val name: String? = null,
    val note: String? = null,
)
