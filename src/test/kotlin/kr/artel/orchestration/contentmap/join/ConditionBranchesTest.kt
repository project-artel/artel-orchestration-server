package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.flatten
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * 실측 WordVenture 캡처(`wv-editor-latest.json`, schema 6)의 **진짜 레코드**로만 검증한다.
 *
 * 손으로 만든 조건 트리는 아무것도 증명하지 못한다 — 우리가 상상한 모양이 문서의 모양이라는 것이
 * 바로 이 단계에서 가장 자주 틀리는 가정이다. 씬 이름 비교의 따옴표, `either` 가 `every` 안에
 * 들어앉은 자리, 가드 test 를 `branch`끼리 공유하는 방식은 전부 실측에서만 나온다.
 *
 * 문서는 한 번만 읽는다(`PER_CLASS` + `@BeforeAll`) — 1.4MB 를 테스트마다 다시 파싱할 이유가 없다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionBranchesTest {

    private lateinit var document: EvidenceDocumentModel

    @BeforeAll
    fun parseOnce() {
        document = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())
    }

    private fun records(type: String): List<EvidenceRecord> = document.types.getValue(type)

    /** 조건 트리 루트가 `either` 이고 `branch`마다 다른 키를 든 실측 레코드들. */
    private fun inputSplittingRecords(): List<EvidenceRecord> =
        records(MAP_MOVE).filter { record ->
            val root = record.condition
            root is ConditionNode.Group && root.kind == GroupKind.EITHER
        }

    private fun ConditionNode.gestureInputs(): List<String> =
        flatten().filterIsInstance<ConditionNode.Gesture>().map { it.input }

    private fun ConditionNode.tests(): List<ConditionNode.Test> = flatten().filterIsInstance<ConditionNode.Test>()

    /**
     * `input_key` 가 단일 컬럼이라 키마다 후보가 따로 나와야 한다. 갈라지지 않으면 실측
     * `Map.MapMove::CharacterMove` 의 `DownArrow` 가 통째로 사라져 "아래 키로 이동한다"는 명세가
     * 영영 만들어지지 않는다.
     */
    @Test
    fun `either 레코드는 키마다 branch 하나로 쪼개진다`() {
        val record = inputSplittingRecords().first { record ->
            record.condition.gestureInputs() == listOf("key:LeftArrow (down)", "key:DownArrow (down)") &&
                record.inputs.any { it.offset == 202 }
        }

        val branches = ConditionBranches.from(record, MAP_SCENE)

        assertThat(branches).hasSize(2)
        assertThat(branches.map { it.gesture?.input })
            .containsExactly("key:LeftArrow (down)", "key:DownArrow (down)")
        assertThat(branches.map { it.branchOffset }).containsExactly(202, 214)
    }

    /**
     * `branch`를 쪼개면서 가드 조건을 흘리면 "언제나 되는 이동"이 되어 거짓 명세가 된다. 실측에서
     * 두 `branch`는 같은 가드 두 개(`MapMove.position == 1` @119, `InteractionLock.IsLocked == 0` @5)를
     * 공유하므로, 쪼갠 뒤에도 양쪽에 그대로 남아 있어야 한다.
     */
    @Test
    fun `쪼갠 branch는 자기 가드 테스트를 그대로 들고 간다`() {
        val record = inputSplittingRecords().first { it.inputs.any { input -> input.offset == 202 } }

        val branches = ConditionBranches.from(record, MAP_SCENE)

        assertThat(branches).allSatisfy { branch ->
            assertThat(branch.condition.tests().map { it.left to it.offset })
                .containsExactlyInAnyOrder(
                    "MapMove.position" to 119,
                    "InteractionLock.IsLocked" to 5,
                )
        }
    }

    /**
     * `either` 를 `every` 로 접으면 "둘 중 하나"가 "둘 다"가 되어 절대 성립하지 않는 조건이 된다.
     * 실측 68건 중 입력을 가르지 않는 64건은 쪼개지도 접지도 않고 그 모양 그대로 남아야 한다 —
     * `Map.MapMove::CharacterMove` 의 `position == 4` 또는 `position == 5` `branch`가 그 경우다.
     */
    @Test
    fun `입력을 가르지 않는 either 는 쪼개지도 every 로 접히지도 않는다`() {
        val record = records(MAP_MOVE).single { candidate ->
            val root = candidate.condition
            root is ConditionNode.Group &&
                root.kind == GroupKind.EVERY &&
                root.parts.any { it is ConditionNode.Gesture && it.offset == 642 } &&
                root.parts.any { it is ConditionNode.Group && it.kind == GroupKind.EITHER }
        }

        val branches = ConditionBranches.from(record, MAP_SCENE)

        assertThat(branches).hasSize(1)
        assertThat(branches.single().condition).isEqualTo(record.condition)
        assertThat(branches.single().condition.flatten().filterIsInstance<ConditionNode.Group>())
            .anySatisfy { group -> assertThat(group.kind).isEqualTo(GroupKind.EITHER) }
    }

    /**
     * `Scenes.GameClearController` 는 `GameClearScene` 과 `GameOverScene` 두 곳에 붙어 있고(실측
     * 씬 오브젝트 `GameClearBackground` · `GameoverBackGround`), 조건 트리가 자기 안에서 그 둘을
     * 가른다. 씬을 보지 않으면 두 화면에 서로의 기능이 섞인다.
     */
    @Test
    fun `GameClearScene 에서는 GameOverScene 쪽 branch가 빠진다`() {
        val gameOverOnly = records(GAME_CLEAR_CONTROLLER).single { record ->
            record.condition.tests().any { it.left == ACTIVE_SCENE_NAME && it.operator == "!=" }
        }

        assertThat(ConditionBranches.from(gameOverOnly, GAME_CLEAR_SCENE)).isEmpty()
        assertThat(ConditionBranches.from(gameOverOnly, GAME_OVER_SCENE)).hasSize(1)
    }

    /**
     * 반대 방향도 같아야 한다. `== "GameClearScene"` `branch`가 `GameOverScene` 에 남으면 게임오버
     * 화면에 "카드를 받는다"는 기능이 생긴다.
     */
    @Test
    fun `GameOverScene 에서는 GameClearScene 쪽 branch가 빠진다`() {
        val gameClearOnly = records(GAME_CLEAR_CONTROLLER).filter { record ->
            record.condition.tests().any { it.left == ACTIVE_SCENE_NAME && it.operator == "==" }
        }

        assertThat(gameClearOnly).hasSize(11)
        assertThat(gameClearOnly).allSatisfy { record ->
            assertThat(ConditionBranches.from(record, GAME_OVER_SCENE)).isEmpty()
            assertThat(ConditionBranches.from(record, GAME_CLEAR_SCENE)).hasSize(1)
        }
    }

    /**
     * 씬 이름 값은 `"\"GameClearScene\""` 처럼 **따옴표까지 값에 들어 있다.** 벗기지 않고 비교하면
     * 실측 12건이 전부 어긋나 어떤 씬에서도 남지 않는다 — 기능이 조용히 0건이 되는 실패다.
     * 씬 조건이 없는 레코드는 그 필터에 걸리지 않아야 한다.
     */
    @Test
    fun `씬 조건이 없는 레코드는 어느 씬에서도 남는다`() {
        val alwaysRecords = records(GAME_CLEAR_CONTROLLER).filter { it.condition is ConditionNode.Always }

        assertThat(alwaysRecords).hasSize(2)
        assertThat(alwaysRecords).allSatisfy { record ->
            assertThat(ConditionBranches.from(record, GAME_CLEAR_SCENE)).hasSize(1)
            assertThat(ConditionBranches.from(record, GAME_OVER_SCENE)).hasSize(1)
        }
    }

    /**
     * 살아남은 `branch`는 다시 쓰지 않는다. `condition_tree` 를 사람이 되짚을 때 보는 것은 원본 모양이고,
     * 우리가 정규화한 모양은 근거 문서 어디에도 없어 대조할 수 없다.
     */
    @Test
    fun `살아남은 branch는 원본 트리를 그대로 들고 있다`() {
        val record = records(GAME_CLEAR_CONTROLLER).first { record ->
            record.condition.tests().any { it.left == ACTIVE_SCENE_NAME && it.operator == "==" }
        }

        assertThat(ConditionBranches.from(record, GAME_CLEAR_SCENE).single().condition)
            .isEqualTo(record.condition)
    }

    /**
     * `branchOffset` 은 한 메서드 안의 서로 다른 지점을 가른다. gesture 가 있으면 그 자리가 곧
     * `branch`를 다르게 만든 자리이므로 그 offset 을 쓴다 — 가드 test 는 `branch`끼리 공유되어 최솟값이
     * 겹친다(실측: 두 `branch` 모두 `InteractionLock.IsLocked` @5 를 든다).
     */
    @Test
    fun `branchOffset 은 gesture 위치를 먼저 쓴다`() {
        val returnRecord = records(MAP_MOVE).single {
            it.recordKind == "candidate" && it.condition.gestureInputs() == listOf("key:Return (down)")
        }

        assertThat(ConditionBranches.from(returnRecord, MAP_SCENE).single().branchOffset).isEqualTo(704)
    }

    /**
     * gesture 가 없으면 `branch` 안 가장 작은 offset 으로 떨어진다. 조건이 `always` 면 IL 위치 자체가
     * 없으므로 null 이어야 한다 — 0 으로 채우면 실제 offset 0 인 `branch`와 구분되지 않는다.
     */
    @Test
    fun `gesture 가 없으면 가장 작은 offset 을 쓰고 always 는 null 이다`() {
        val testOnly = records(MAP_MOVE).first {
            it.condition is ConditionNode.Test && (it.condition as ConditionNode.Test).left == "stagePosition"
        }
        val always = records(MAP_MOVE).first { it.condition is ConditionNode.Always }

        assertThat(ConditionBranches.from(testOnly, MAP_SCENE).single().branchOffset).isEqualTo(3)
        assertThat(ConditionBranches.from(always, MAP_SCENE).single().branchOffset).isNull()
    }

    /**
     * 문서 전체를 훑어 폭발과 충돌을 함께 막는다.
     *
     * 실측 `types` 318 레코드 중 입력을 가르는 것은 4건뿐이라 `GameClearScene` 기준 `branch`는 321개다
     * (318 + 4 − 씬 조건으로 죽는 1건). 순수 상태 논리합까지 펼쳤다면 `Cards.CardManager` 의
     * 중첩 `either` 9개짜리 레코드가 곱해져 수십 배가 된다. 한 레코드 안 `branch`들의 offset 이 겹치면
     * 적재기가 두 후보를 하나로 볼 수 있으므로 그것도 같이 본다.
     */
    @Test
    fun `문서 전체를 갈라도 branch 수가 폭발하지 않고 offset 이 겹치지 않는다`() {
        val branchesByRecord = document.types.values.flatten()
            .map { it to ConditionBranches.from(it, GAME_CLEAR_SCENE) }

        assertThat(branchesByRecord.sumOf { (_, branches) -> branches.size }).isEqualTo(321)
        assertThat(branchesByRecord.count { (_, branches) -> branches.size > 1 }).isEqualTo(4)
        assertThat(branchesByRecord.count { (_, branches) -> branches.isEmpty() }).isEqualTo(1)
        assertThat(branchesByRecord).allSatisfy { (_, branches) ->
            val offsets = branches.map { it.branchOffset }
            assertThat(offsets).doesNotHaveDuplicates()
        }
    }

    /**
     * `branch`마다 gesture 는 최대 하나여야 한다. 둘이면 `input_key` 한 칸에 무엇을 적을지 정할 수 없고,
     * 하나를 고르는 순간 나머지가 근거 없이 사라진다.
     */
    @Test
    fun `branch 하나에 gesture 는 최대 하나다`() {
        val branches = document.types.values.flatten()
            .flatMap { ConditionBranches.from(it, GAME_CLEAR_SCENE) }

        assertThat(branches).allSatisfy { branch ->
            assertThat(branch.condition.gestureInputs().size).isLessThanOrEqualTo(1)
        }
    }

    private companion object {
        const val MAP_MOVE = "Map.MapMove"
        const val GAME_CLEAR_CONTROLLER = "Scenes.GameClearController"
        const val MAP_SCENE = "Map_scene"
        const val GAME_CLEAR_SCENE = "GameClearScene"
        const val GAME_OVER_SCENE = "GameOverScene"
        const val ACTIVE_SCENE_NAME = "SceneManager.GetActiveScene().name"
    }
}
