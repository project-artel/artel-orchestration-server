package kr.artel.orchestration.scenecontext.dto

/**
 * agent-server 가 **런 시작에 한 번** 받아 두는 씬별 맥락(ARTEL-611).
 *
 * ARTEL-612 가 이것을 메모리에 들고 매 턴 **현재 씬의 조각만** 프롬프트에 그린다. 그래서 이
 * payload 의 설계 축은 둘이다 — 런당 왕복 하나, 그리고 프롬프트가 감당할 수 있는 부피.
 *
 * **브라우저 조회(ARTEL-446)와 겸하지 않는다.** 한 엔드포인트로 겸하면 사람이 읽을 것의 요구가
 * 프롬프트의 부피를 정하게 되고, 그 순간 어느 쪽도 줄일 수 없어진다.
 *
 * id 계열은 전부 문자열이다. 다른 payload 와 같은 이유로 — 64비트 정밀도 손실을 피한다.
 *
 * @property contentMapId 고른 지도. **null 이 정상이다** — 아직 아무도 `evidence` 를 올리지 않은
 *   빌드에서 이 응답은 404 가 아니라 200 이고, 그때 이 값과 [capture] 가 비어 있다.
 * @property capture `editor` · `editor-play` · `player`. 어느 관측으로 만든 지도인지에 따라 같은
 *   필드가 다른 뜻이라(V40), agent 가 무엇을 읽고 있는지 알 수 있게 함께 낸다.
 * @property scenes 지도가 아는 씬이 이름 오름차순으로 먼저 오고, 앵커에만 있는 씬이 역시 이름
 *   오름차순으로 뒤에 붙는다. 순서를 고정하는 것은 취향이 아니다 — 이 목록이 프롬프트에 실려
 *   프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨진다. 두 무리를 섞어
 *   한 번에 정렬하지 않는 것은 지도에 씬이 생기고 사라져도 지도 쪽 블록의 순서가 흔들리지 않게
 *   하기 위해서다.
 */
data class SceneContextResponse(
    val gameBuildId: String,
    val contentMapId: String? = null,
    val capture: String? = null,
    val scenes: List<SceneContextEntry> = emptyList(),
)

/**
 * 씬 하나에서 **무엇을 할 수 있고 무엇이 그 씬에만 참인가**.
 *
 * **씬 이름이 두 반쪽을 잇는 유일한 키다.** 앵커는 content map 과 대조하지 않고 저장되므로
 * (ARTEL-591, V55), content map 이 들어 본 적 없는 씬 이름을 든 앵커가 정상적으로 존재한다.
 * 그런 씬도 이 목록에 들어오고, 그때 [capabilities] 가 비고 [knownToContentMap] 이 false 다.
 *
 * 반대로 지도에는 있는데 할 수 있는 것이 하나도 없는 씬도 남긴다. "이 씬은 아는데 할 게 없다"와
 * "이 씬을 모른다"는 다른 답이고, 뭉개면 agent 가 지도에 있는 씬을 미지의 씬으로 읽는다.
 *
 * @property knownToContentMap 이 씬이 지도에 있는가. false 면 앵커 지식만으로 들어온 씬이다.
 * @property sceneSummary 지도가 아는 씬 설명. 앵커로만 들어온 씬에서는 null 이다.
 * @property capabilities **agent 가 직접 할 수 있는 것.** `status` 가 `not-a-step` 이 아닌 행이고,
 *   TC 생성기가 받는 것과 같은 집합이다. 접힌(`merged_into`) 행은 뷰에서 이미 빠진다.
 * @property notAStepCapabilities **누를 수 없고 일어나는 것.** `status = 'not-a-step'` 인 행이다.
 *   [capabilities] 와 같은 표에서 오고 모양도 같지만, 목록을 가르지 않으면 실측 51 개의
 *   조작이 418 개 사이에 묻혀 agent 가 무엇을 시도해야 할지 흐려진다(ARTEL-680). 이쪽은
 *   시도할 목록이 아니라 **일어난 것을 알아볼 목록**이다 — "적을 처치하면 보상을 받는다" 처럼
 *   화면을 보고 확인해 `capability` 에 적는 대상(ARTEL-644, ARTEL-645).
 *
 *   칸 이름에 `status` 값을 그대로 박은 것은, 이 목록이 무슨 축으로 갈렸는지를 payload 만 보는
 *   쪽도 알 수 있게 하기 위해서다. 각 줄의 `status` 를 읽으면 같은 값이 나온다.
 * @property knowledge 이 씬에 묶인 지식. **본문은 없다** — 이 블록은 매 모델 호출마다 다시
 *   그려지므로 본문을 실으면 그 비용을 런 내내 매 턴 다시 낸다.
 */
data class SceneContextEntry(
    val sceneName: String,
    val knownToContentMap: Boolean,
    val sceneSummary: String? = null,
    val capabilities: List<SceneCapabilityView> = emptyList(),
    val notAStepCapabilities: List<SceneCapabilityView> = emptyList(),
    val knowledge: List<SceneKnowledgeView> = emptyList(),
)

/**
 * 프롬프트 한 줄이 될 capability 하나.
 *
 * **`condition_tree` · `evidence` 주소(`entry_id`) · 효과는 여기 없다.** 프롬프트에 그릴 수 없는 것을
 * 실으면 런당 payload 만 부풀고 agent 는 그것을 읽지 않는다. 필요해지면 그때 브라우저 조회가
 * 이미 내주고 있다.
 *
 * @property capabilityId 액션·관측을 되짚을 때 쓰는 표시·조인용 id.
 * @property capabilityKey 재적재를 넘어 살아남는 참조 키. **기억해 둘 값은 이쪽이다.** evidence
 *   출신이 아니면 null 이다.
 * @property status 세 축에서 유도된 값. "TC 로 만들 수 있나"에 대한 답이다.
 *   [SceneContextEntry.notAStepCapabilities] 의 줄에서는 항상 `not-a-step` 이고, 나머지 줄에서는
 *   결코 그 값이 아니다 — 목록이 갈린 축이 이 칸이다.
 * @property actionability 실행 가능성. 이 조작을 실제로 할 수 있는가.
 * @property observability 관측 가능성. `unobservable` 이어도 조작 스텝으로는 쓸 수 있다.
 * @property applicability 적용 가능성. `not-applicable` 은 이 빌드에 아예 없다.
 * @property verification 실행으로 확인됐나. [status] 와 다른 축이다 — 이쪽은 우리가 눌러 봤는지고
 *   저쪽은 누를 수 있는지다. agent 가 무엇을 먼저 시도할지 고르는 재료다.
 * @property scenePresence 이 줄이 왜 이 `scene` 에 있나(ARTEL-460). `placed` 는 근거가 이 `scene` 에
 *   놓은 것이고, `persistent-unconfirmed` 는 `scene` 을 넘어 살아남는 오브젝트가 여기 있다는 사실뿐
 *   이라 **여기서 되는지는 아무도 안 봤다.** 그 둘을 같은 줄로 읽으면 agent 는 `TurnBattleScene` 의
 *   목록에 딸려 온 tutorial capability 를 그 `scene` 의 사실로 읽는다. `persistent-evidenced` 는
 *   근거가 이 `scene` 을 지목한 것이고, 무엇을 읽고 그랬는지는 `capability_proof` 가 든다
 * @property repeatUntilDone 한 번인지 끝날 때까지인지. `false` 가 기본이라 이 칸을 모르는
 *   소비자도 기존과 같이 읽는다.
 * @property controlSelectorHint **조준 키가 아니다.** 형제 인덱스가 박힌 경로라 런마다 흔들리고,
 *   액션 프로토콜은 애초에 int instance id 를 받는다. 이름에 `Hint` 를 박아 둔 것은 이 값으로
 *   맞추려 드는 것을 막기 위해서다 — 조준은 [controlPath] · [controlLabel] 과 게임 상태
 *   프레임이 맡는다.
 */
data class SceneCapabilityView(
    val capabilityId: String,
    val capabilityKey: String? = null,
    val summary: String,
    val givenText: String? = null,
    val interaction: String,
    val inputKey: String? = null,
    val controlPath: String? = null,
    val controlLabel: String? = null,
    val status: String,
    val actionability: String,
    val observability: String,
    val applicability: String,
    val verification: String,
    val scenePresence: String,
    val repeatUntilDone: Boolean,
    val controlSelectorHint: String? = null,
)

/**
 * 이 씬에서만 참인 사실 한 줄(ARTEL-591 의 앵커).
 *
 * **id 와 요약뿐이다.** 본문(`description`)은 담지 않는다 — agent 는 필요할 때 [knowledgeId] 로
 * 검색해 본문을 가져올 수 있고, 그 편이 매 턴 다시 그려지는 블록에 본문을 상주시키는 것보다
 * 훨씬 싸다.
 *
 * **앵커가 없는 지식은 여기 없다.** 그것은 게임 전체의 사실이고 지식창고의 대부분이라, 이
 * 응답에 담으면 "씬별"이라는 말이 뜻을 잃는다. 그쪽은 검색이 답한다.
 *
 * 화면(`screen_id`)까지는 내리지 않는다. 화면 판정은 ARTEL-453 이 먼저이고 이 응답은 씬 단위다.
 */
data class SceneKnowledgeView(
    val knowledgeId: String,
    val summary: String,
)
