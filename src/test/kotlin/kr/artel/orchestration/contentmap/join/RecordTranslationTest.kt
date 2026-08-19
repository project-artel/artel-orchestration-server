package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.InputPhase
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.flatten
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * 번역 규칙을 실측 레코드로 검증한다.
 *
 * 손으로 만든 레코드는 우리가 상상한 어휘만 확인해 준다 — 문서가 실제로 쓰는 값이 세 개뿐인지,
 * gesture 문자열이 정말 `key:Return (down)` 모양인지, `inputs[]` 와 조건 트리가 어긋나는 자리가
 * 있는지는 문서를 읽어야만 알 수 있고, 그 어긋남이 이 파일이 막으려는 실패다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordTranslationTest {

    private lateinit var document: EvidenceDocumentModel

    @BeforeAll
    fun parseOnce() {
        document = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())
    }

    private fun records(type: String): List<EvidenceRecord> = document.types.getValue(type)

    private fun ConditionNode.gestureInputs(): List<String> =
        flatten().filterIsInstance<ConditionNode.Gesture>().map { it.input }

    private fun ConditionNode.tests(): List<ConditionNode.Test> = flatten().filterIsInstance<ConditionNode.Test>()

    private fun onlyBranch(record: EvidenceRecord, scene: String): ConditionBranch =
        ConditionBranches.from(record, scene).single()

    /** 실측 인스펙터 배선 7건 중 하나를 문서에서 그대로 집어 온다. 배선 판정은 다른 단계의 몫이라 값만 쓴다. */
    private fun bindingFor(targetType: String, method: String): ControlBinding {
        val obj = document.allObjects.single { candidate ->
            candidate.components.any { component ->
                component.calls.any { it.targetType == targetType && it.method == method }
            }
        }
        val call = obj.components.flatMap { it.calls }.first { it.targetType == targetType && it.method == method }
        return ControlBinding(
            placement = ScenePlacement(obj.scene, obj.path, obj.selector, obj.label),
            event = call.event,
            via = WiringPath.ENTRY,
        )
    }

    /**
     * gesture 문자열은 `<kind>:<control> (<phase>)` 한 덩어리로 렌더된 값이라, 쪼개지 않으면
     * `input_key` 칸에 `key:Return (down)` 전체가 들어간다. 실행 에이전트는 그런 이름의 키를
     * 찾지 못한다.
     */
    @Test
    fun `key Return down 은 press·Return·down 으로 번역된다`() {
        val record = records(MAP_MOVE).single {
            it.recordKind == "candidate" && it.condition.gestureInputs() == listOf("key:Return (down)")
        }

        val translated = RecordTranslation.interactionOf(onlyBranch(record, MAP_SCENE), binding = null)

        assertThat(translated.interaction).isEqualTo(Interaction.PRESS)
        assertThat(translated.inputKey).isEqualTo("Return")
        assertThat(translated.inputPhase).isEqualTo(InputPhase.DOWN)
    }

    /**
     * 근거가 "아무 키"라고 말한 자리에 실제 키 하나를 골라 적으면 거짓 명세가 된다. sentinel 로
     * 남겨야 실행 에이전트가 아무 키나 골라 보낸다. 실측 `key:any (down)` gesture 는 11건이다.
     */
    @Test
    fun `키를 지목하지 않은 근거는 sentinel 키로 남는다`() {
        val record = records(GAME_CLEAR_CONTROLLER).first {
            it.recordKind == "candidate" && it.condition.gestureInputs() == listOf("key:any (down)")
        }

        val translated = RecordTranslation.interactionOf(onlyBranch(record, GAME_CLEAR_SCENE), binding = null)

        assertThat(translated.interaction).isEqualTo(Interaction.PRESS)
        assertThat(translated.inputKey).isEqualTo(Interaction.ANY_INPUT_KEY)
        assertThat(translated.inputPhase).isEqualTo(InputPhase.DOWN)
    }

    /**
     * 배선된 버튼에는 키 조건이 없다 — 누르는 것 자체가 조작이라 조건 트리에 gesture 가 없다.
     * 여기서 [Interaction.NONE] 을 내면 실측 배선 7건이 전부 "TC 가 지시할 수 없음"이 된다.
     * `button_click` 같은 SDK 프로토콜 이름을 내는 것도 금지다 — 이 칸은 의도를 담는다.
     */
    @Test
    fun `gesture 가 없고 배선만 있으면 click 이다`() {
        val record = records(BACK_BUTTON).single()

        val translated = RecordTranslation.interactionOf(
            branch = onlyBranch(record, MAP_SCENE),
            binding = bindingFor(BACK_BUTTON, "BackToMain"),
        )

        assertThat(translated.interaction).isEqualTo(Interaction.CLICK)
        assertThat(translated.interaction.wire).isEqualTo("click")
        assertThat(translated.inputKey).isNull()
    }

    /**
     * gesture 도 배선도 없으면 조작이 없는 것이다. 실측 `Scenes.TitleSceneManager::Start()` 는
     * 수명주기라 TC 가 지시할 수 없고, 그 사실을 `none` 이 담는다.
     */
    @Test
    fun `gesture 도 배선도 없으면 none 이다`() {
        val record = records(TITLE_SCENE_MANAGER).first {
            it.source.endsWith("::Start()") && it.condition is ConditionNode.Always
        }

        val translated = RecordTranslation.interactionOf(onlyBranch(record, TITLE_SCENE), binding = null)

        assertThat(translated.interaction).isEqualTo(Interaction.NONE)
        assertThat(translated.inputKey).isNull()
        assertThat(translated.inputPhase).isNull()
    }

    /**
     * `inputs[]` 와 조건 트리는 1:1 이 아니다. 실측 `Story.StoryController::IsAdvanceKeyDown` 은
     * `inputs[]` 에 `key:any` 와 `mouse:2` 를 함께 들었지만 트리에는 `key:any` gesture 하나뿐이고,
     * 문서가 스스로 `input-not-branched` gap 을 남긴다. `inputs[]` 를 권위로 삼으면 어느 입력이
     * 어느 갈래의 것인지 알 수 없는 채로 `input_key` 한 칸을 채우게 된다 — 트리를 따르고, 빠진
     * 입력은 문서의 gap 토큰이 계속 말하게 둔다.
     */
    @Test
    fun `inputs 에만 있는 마우스 입력은 조작을 바꾸지 못하고 gap 으로 남는다`() {
        val record = records(STORY_CONTROLLER).single { it.condition is ConditionNode.Gesture }
        assertThat(record.inputs.map { it.kind }).contains("mouse")

        val branch = onlyBranch(record, STORY_SCENE)
        val translated = RecordTranslation.interactionOf(branch, binding = null)

        assertThat(translated.interaction).isEqualTo(Interaction.PRESS)
        assertThat(translated.inputKey).isEqualTo(Interaction.ANY_INPUT_KEY)
        assertThat(RecordTranslation.gapsOf(record, branch)).contains("input-not-branched")
    }

    /**
     * 문서 어휘가 셋뿐이라는 것이 이 매핑의 전제다. 넷째 값이 생기면 [AnalysisConfidence.UNRESOLVED]
     * 로 떨어져 눈에 띄어야 하므로, 전제 자체를 문서에서 확인한다.
     */
    @Test
    fun `문서 확신도 어휘는 verified·derived·partial 셋뿐이다`() {
        val byValue = document.types.values.flatten().groupingBy { it.confidence }.eachCount()

        assertThat(byValue).containsOnlyKeys("verified", "derived", "partial")
        assertThat(byValue).containsEntry("verified", 85)
        assertThat(byValue).containsEntry("derived", 171)
        assertThat(byValue).containsEntry("partial", 62)
    }

    /**
     * 세 어휘가 전부 스키마 어휘로 옮겨져야 한다. `partial` 을 [AnalysisConfidence.UNRESOLVED] 로
     * 내리면 실제보다 낮게 적어 쓸 수 있는 명세 62건을 버린다 — 그 62건은 조건 일부를 못 합친
     * 것이지 아무것도 못 푼 것이 아니다.
     */
    @Test
    fun `문서 확신도 세 어휘가 전부 스키마 어휘로 옮겨진다`() {
        assertThat(RecordTranslation.confidenceOf("verified")).isEqualTo(AnalysisConfidence.EXACT)
        assertThat(RecordTranslation.confidenceOf("derived")).isEqualTo(AnalysisConfidence.DERIVED)
        assertThat(RecordTranslation.confidenceOf("partial")).isEqualTo(AnalysisConfidence.AMBIGUOUS)

        assertThat(document.types.values.flatten().map { RecordTranslation.confidenceOf(it.confidence) })
            .doesNotContain(AnalysisConfidence.UNRESOLVED)
    }

    /**
     * 문서는 파생물이라 어휘가 언제든 는다. 모르는 값이 [AnalysisConfidence.EXACT] 로 떨어지면
     * 확신도가 조용히 부풀어 오른 채 저장되고, 그 행은 아무도 다시 보지 않는다.
     */
    @Test
    fun `모르는 확신도는 exact 로 올라가지 않는다`() {
        assertThat(RecordTranslation.confidenceOf("speculative")).isEqualTo(AnalysisConfidence.UNRESOLVED)
        assertThat(RecordTranslation.confidenceOf("")).isEqualTo(AnalysisConfidence.UNRESOLVED)
    }

    /**
     * `context` 가 null 인 test 는 주어를 못 찾은 것이라 given 으로 쓸 수 없다(실측 47건, 전부
     * `subjectLost` 를 동반한다). 여기서 주어를 상상해 채우는 것이 가장 비싼 거짓 명세라,
     * 문서가 말하지 않아도 우리가 표시한다.
     */
    @Test
    fun `주어를 못 찾은 조건에는 subject-null 이 붙는다`() {
        val record = records(STORY_CONTROLLER).first { it.condition.tests().count { test -> test.context == null } == 2 }

        val gaps = RecordTranslation.gapsOf(record, onlyBranch(record, STORY_SCENE))

        assertThat(gaps).contains(EvidenceGap.SUBJECT_NULL.wire)
        // 주어 없는 test 가 둘이어도 토큰은 하나다. 같은 사실을 두 번 적으면 세는 쪽이 부풀어 오른다.
        assertThat(gaps.count { it == EvidenceGap.SUBJECT_NULL.wire }).isEqualTo(1)
        // 문서가 준 토큰은 그대로 남는다 — 우리가 더한 것이 문서의 말을 덮으면 안 된다.
        assertThat(gaps).containsAll(record.gaps)
    }

    /**
     * [EvidenceGap] 이 모르는 토큰도 버리지 않는다. 실측 6종 중 `composed-on-same-object` 를 포함한
     * 4종은 아직 열거값이 없지만, 모르는 토큰도 "이 조건을 다 안다고 치지 말라"는 말이다.
     * 주어가 멀쩡한 조건에 `subject-null` 을 덧붙이지 않는다는 것도 같은 자리에서 확인한다.
     */
    @Test
    fun `열거값에 없는 gap 토큰도 그대로 통과한다`() {
        val record = records(GAME_CLEAR_CONTROLLER).first { it.gaps == listOf("composed-on-same-object") }
        assertThat(EvidenceGap.from("composed-on-same-object")).isNull()

        val gaps = RecordTranslation.gapsOf(record, onlyBranch(record, GAME_CLEAR_SCENE))

        assertThat(gaps).containsExactly("composed-on-same-object")
    }

    private companion object {
        const val MAP_MOVE = "Map.MapMove"
        const val GAME_CLEAR_CONTROLLER = "Scenes.GameClearController"
        const val STORY_CONTROLLER = "Story.StoryController"
        const val BACK_BUTTON = "Cards.BackButton"
        const val TITLE_SCENE_MANAGER = "Scenes.TitleSceneManager"
        const val MAP_SCENE = "Map_scene"
        const val GAME_CLEAR_SCENE = "GameClearScene"
        const val STORY_SCENE = "StoryScene"
        const val TITLE_SCENE = "TitleScene"
    }
}
