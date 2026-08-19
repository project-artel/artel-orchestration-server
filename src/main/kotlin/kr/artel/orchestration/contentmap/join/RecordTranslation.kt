package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.InputPhase
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.flatten

/**
 * 근거 문서의 어휘를 **DB 어휘로 옮긴다.**
 *
 * 두 어휘는 일부러 다르다. 문서는 "IL 을 얼마나 읽었나"를 말하고 스키마는 "이 기능을 얼마나
 * 믿을 수 있나"를 말한다. 옮기는 자리가 여기 하나뿐이어야 어긋난 값을 한 군데서 고칠 수 있다 —
 * 적재기가 문자열을 직접 비교하기 시작하면 어휘가 늘 때 조용히 새는 분기가 생긴다.
 *
 * 계산은 순수하다. 씬도 배선도 여기서 찾지 않는다 — 이미 찾은 것을 인자로 받는다.
 */
object RecordTranslation {

    /**
     * 이 갈래가 요구하는 조작을 [Interaction] 어휘로 옮긴다.
     *
     * **`condition` 의 gesture 노드가 권위다. `inputs[]` 는 아니다.** 둘은 1:1 이 아니다 — 실측에서
     * `Story.StoryController::IsAdvanceKeyDown` 2건은 `inputs[]` 에 `key:any` 와 `mouse:2` 를 함께
     * 들고 있는데 조건 트리에는 `key:any` gesture 하나뿐이고, 문서 스스로 `input-not-branched` gap
     * 을 남긴다. `inputs[]` 는 메서드 전체의 평평한 목록이라 **어느 입력이 어느 갈래의 것인지**
     * 말하지 못하고, `capability.input_key` 는 단일 컬럼이다. 둘 중 하나를 골라야 한다면 갈래를
     * 아는 쪽이어야 한다. `inputs[]` 에만 있던 입력은 사라지지 않는다 — 문서가 남긴
     * `input-not-branched` 토큰이 [gapsOf] 를 그대로 통과해 "이 조작은 이게 전부가 아니다"를 남긴다.
     *
     * gesture 가 없고 [binding] 만 있으면 [Interaction.CLICK] 이다 — 컨트롤이 부르는 메서드인데
     * 키 조건이 없다는 것은 그 컨트롤을 누르는 것 자체가 조작이라는 뜻이다(실측 인스펙터 배선 7건).
     * 둘 다 없으면 [Interaction.NONE] — 타이머·코루틴·수명주기라 TC 가 지시할 수 없다.
     *
     * SDK 의 액션 프로토콜 메서드 이름(`button_click` 등)은 절대 내지 않는다. 저 이름은 배포마다
     * 바뀌고, 이 컬럼이 담는 것은 프로토콜이 아니라 의도다.
     */
    fun interactionOf(branch: ConditionBranch, binding: ControlBinding?): BranchInteraction {
        val gesture = branch.gesture?.rendered()
        return when (gesture?.kind) {
            KEY_GESTURE -> BranchInteraction(
                interaction = Interaction.PRESS,
                // 근거의 `any` 와 sentinel [Interaction.ANY_INPUT_KEY] 는 오늘 같은 문자열이지만
                // 같은 어휘가 아니다. 한쪽이 바뀌면 여기서 깨져야지, 실행 에이전트가 `any` 라는
                // 이름의 키를 찾다가 런타임에 깨지면 안 된다.
                inputKey = if (gesture.control == DOCUMENT_ANY_KEY) Interaction.ANY_INPUT_KEY else gesture.control,
                // 모르는 phase 는 null 로 둔다. `ck_capability_press_needs_key` 가 요구하는 것은
                // 키이지 phase 라, 여기서 `down` 을 지어내면 근거에 없는 말을 적게 된다.
                inputPhase = InputPhase.from(gesture.phase),
            )

            // 실측 gesture 25건은 전부 `key:` 다. 마우스는 `inputs[]` 에만 2건 있어 이 갈래는 아직
            // 데이터로 확인되지 않았다 — 오브젝트 위에서 누르는 마우스 버튼은 클릭이므로 그렇게
            // 옮기되, 버튼 번호를 `input_key` 에 적지는 않는다(그 컬럼은 키 이름을 담는다).
            MOUSE_GESTURE -> BranchInteraction(Interaction.CLICK, null, null)

            // 못 읽은 gesture 문자열은 조작이 없는 것으로 치지 않고 배선 쪽 판정으로 떨어진다.
            // 문서 세대가 바뀌어 표기가 달라지면 여기가 조용히 `none` 을 쏟아내면 안 된다.
            else -> if (binding != null) {
                BranchInteraction(Interaction.CLICK, null, null)
            } else {
                BranchInteraction(Interaction.NONE, null, null)
            }
        }
    }

    /**
     * 문서의 확신도 어휘를 [AnalysisConfidence] 로 옮긴다.
     *
     * 문서 어휘는 `verified`(85) · `derived`(171) · `partial`(62) 세 개다(실측, `types` 기준).
     *
     * - `verified` → [AnalysisConfidence.EXACT]. 문서가 그 자리에서 확정했다는 뜻이고, 스키마의
     *   `exact` 도 "그 자리에서 확정했다"다. 뜻이 같다.
     * - `derived` → [AnalysisConfidence.DERIVED]. 어휘가 그대로 겹친다.
     * - `partial` → [AnalysisConfidence.AMBIGUOUS]. "일부만 읽었다"이지 "못 풀었다"가 아니다.
     *   실측 62건이 **전부** gap 토큰을 동반한다(`callee-condition-not-composed` ·
     *   `composed-on-same-object` 등) — 조건의 일부를 못 합쳤다는 뜻이라 후보가 하나로 좁혀지지
     *   않은 상태고, 그것이 `ambiguous` 다. [AnalysisConfidence.UNRESOLVED] 로 내리면 실제보다
     *   낮게 적어 쓸 수 있는 명세를 버리게 된다.
     * - 그 밖 → [AnalysisConfidence.UNRESOLVED]. **모르는 값은 절대 `exact` 로 올리지 않는다.**
     *   문서는 파생물이라 언제든 어휘가 늘고, 그때 기본값이 `exact` 면 확신도가 조용히 부풀어
     *   오른 채 저장된다. 가장 흐린 값으로 떨어뜨려야 그 행이 눈에 띈다.
     */
    fun confidenceOf(documentConfidence: String): AnalysisConfidence = when (documentConfidence) {
        DOCUMENT_VERIFIED -> AnalysisConfidence.EXACT
        DOCUMENT_DERIVED -> AnalysisConfidence.DERIVED
        DOCUMENT_PARTIAL -> AnalysisConfidence.AMBIGUOUS
        else -> AnalysisConfidence.UNRESOLVED
    }

    /**
     * `capability_evidence.gaps` 에 들어갈 토큰들 — 문서가 말한 것 + 이 갈래를 보고 우리가 판정한 것.
     *
     * **문서 토큰은 손대지 않고 그대로 통과시킨다.** [EvidenceGap] 이 모르는 토큰도 버리지 않는다 —
     * 실측 6종 중 `singleton-plumbing` · `composed-on-same-object` · `input-not-branched` ·
     * `reached-through-delegate` 4종은 아직 열거값이 없지만, 모르는 토큰도 "이 조건을 다 안다고
     * 치지 말라"는 말이다. 아는 것만 남기면 그 경고가 사라진 채 저장된다.
     *
     * 여기서 더하는 것은 [EvidenceGap.SUBJECT_NULL] 하나다. `context` 가 null 인 test 는 주어를
     * 못 찾은 것이고(실측 47건, 전부 `subjectLost` 를 동반한다), 그 조건은 given 으로 쓸 수 없다.
     * 주어를 상상해 채우는 것이 가장 비싼 거짓 명세라 문서 대신 우리가 표시한다.
     *
     * 판정은 갈래 전체를 편 결과로 한다 — `either` 안이든 밖이든 주어가 없으면 없는 것이다.
     */
    fun gapsOf(record: EvidenceRecord, branch: ConditionBranch): List<String> {
        val subjectLost = branch.condition.flatten()
            .filterIsInstance<ConditionNode.Test>()
            .any { it.context == null }
        // `distinct()` 는 문서가 같은 토큰을 두 번 준 경우와, 한 갈래에 주어 없는 test 가 여럿인
        // 경우(실측: `Story.StoryController::SwitchBackground` 는 2건)를 함께 눌러 준다.
        return (record.gaps + listOfNotNull(EvidenceGap.SUBJECT_NULL.wire.takeIf { subjectLost })).distinct()
    }

    private const val DOCUMENT_VERIFIED = "verified"
    private const val DOCUMENT_DERIVED = "derived"
    private const val DOCUMENT_PARTIAL = "partial"

    private const val KEY_GESTURE = "key"
    private const val MOUSE_GESTURE = "mouse"

    /** 근거가 "특정 키를 지목하지 않았다"를 적는 표기. [Interaction.ANY_INPUT_KEY] 와 짝이다. */
    private const val DOCUMENT_ANY_KEY = "any"

    /**
     * `key:Return (down)` · `key:any (down)` · `mouse:2 (down)` 형태.
     *
     * 컨트롤 이름을 `.+` 로 받는 이유: 키 이름에 무엇이 들어올지 근거가 약속하지 않았고, 좁게
     * 잡아 매칭이 실패하면 조작이 통째로 `none` 이 된다. 반대로 `kind` 와 `phase` 는 문서가 닫힌
     * 어휘로 주므로 좁게 잡아 이상한 값이 여기서 걸리게 한다.
     */
    private val RENDERED_GESTURE = Regex("""^(\w+):(.+) \((\w+)\)$""")

    private fun ConditionNode.Gesture.rendered(): RenderedGesture? =
        RENDERED_GESTURE.matchEntire(input)?.destructured
            ?.let { (kind, control, phase) -> RenderedGesture(kind, control, phase) }

    private data class RenderedGesture(val kind: String, val control: String, val phase: String)
}

/**
 * 한 갈래가 요구하는 조작. `capability` 의 `interaction` · `input_key` · `input_phase` 세 칸이다.
 *
 * 세 값을 따로 돌려주지 않는 이유: `press` 인데 키가 없으면 `ck_capability_press_needs_key` 가
 * 거절한다. 함께 정해지는 값은 함께 다녀야 중간에서 한 칸만 갈아끼우는 일이 생기지 않는다.
 */
data class BranchInteraction(
    val interaction: Interaction,
    /** [Interaction.PRESS] 일 때의 키 이름. 근거가 키를 지목하지 않았으면 [Interaction.ANY_INPUT_KEY]. */
    val inputKey: String?,
    /** 문서가 준 phase. 실측은 전부 `down` 이다. 모르는 표기면 null. */
    val inputPhase: InputPhase?,
)
