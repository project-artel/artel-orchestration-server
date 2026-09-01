package kr.artel.orchestration.testcase

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.dto.AuthoringTestCase
import kr.artel.orchestration.testcase.service.TestCaseService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 저작이 **브리지를 지어내지 않게** 재료를 준다(ARTEL-606).
 *
 * 실측(런 159·160)에서 시나리오마다 `case_id` 없는 "…상태로 준비한다"가 두세 개씩 나왔다.
 * 케이스가 `position == 1 인 상태에서` 라고만 하고 position 이 무엇을 하면 1이 되는지는 말하지
 * 않아서다. QA 실행이 따라갈 것이 없는 문장이라, 지어내는 것 자체가 이 시스템이 없애려는 것이다.
 *
 * `AuthoringTestCase.state_after` 는 **이미 계약에 있었다.** 다만 구버전 엑셀 경로가 넣던 메타
 * 칸에서 읽어서, 지도가 낸 케이스는 전량이 빈 map 이었다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthoringStateAfterGoldenTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var testCases: TestCaseService

    private lateinit var cases: List<AuthoringTestCase>

    @BeforeAll
    fun ingestAndRead() = runBlocking {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "state-after", genre = "RPG", createdAt = now, updatedAt = now)
        )
        // 프로젝트 참여자만 목록을 받는다. 실재하는 사용자여야 외래 키가 선다.
        val userId = users.save(AppUserEntity(displayName = "state-after tester", createdAt = now, updatedAt = now)).id!!
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "gen", createdAt = now, updatedAt = now)
        )
        val map = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 6, capture = Capture.EDITOR.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3", backend = "mono", development = true, sdkVersion = "0.1.0",
            )
        )
        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/${map.id}/wv-editor-latest.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)
        ingest.ingest(
            documents.save(
                ContentMapDocumentEntity(
                    contentMapId = map.id!!, objectKey = objectKey,
                    contentHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) },
                    byteSize = bytes.size.toLong(),
                )
            )
        )
        cases = testCases.getAuthoringCases(project.id!!, userId)
    }

    /**
     * **저작이 받는 목록에 "무엇이 바뀌나"가 실린다.**
     *
     * 이 자리가 비어 있어서 모델이 브리지를 지어냈다. 실측 36건 중 **19건**이 뒤에 무언가를
     * 남긴다. 나머지는 표시만 바뀌거나 보기만 하는 것이라 남는 상태가 없다.
     *
     * 49건 중 19건 → 42건 중 23건 → 36건 중 19건으로 왔다. 마지막 걸음은 같은 코드가 두 경로로
     * 닿아 두 벌이던 케이스를 접은 것이다(ARTEL-645). 접힌 넷은 전부 짝이 남아 있으므로 이을
     * 자리가 준 것이 아니라 **같은 자리를 두 번 세던 것을 한 번 센다.**
     *
     * 값은 셋이고(`position` · `stagePosition` · `flag`) 거기에 도착 화면(`scene`)이 더해진다.
     */
    @Test
    fun `케이스가 무엇을 바꾸는지 말한다`() {
        // 20 → 33(ARTEL-681). 관측이 남기는 상태도 뒤 스텝의 전제를 만들어 준다.
        assertThat(cases.count { it.stateAfter.isNotEmpty() }).isEqualTo(33)
        // 관측이 들어오며 셋이 늘었다(ARTEL-681) — 대화 스트리밍과 체력 표시다. 게임이 스스로
        // 바꾸는 값이라, 조작만 볼 때는 아무도 말하지 않던 자리다.
        assertThat(cases.flatMap { it.stateAfter.keys }.distinct())
            .containsExactlyInAnyOrder(
                "position", "stagePosition", "flag", "scene",
                "streamingText", "streamingCoroutine", "HpText",
            )
    }

    /**
     * **얼마나 이어지나.**
     *
     * 전제가 있는 케이스 중 **12건**이 다른 케이스가 만들어 주는 상태를 요구한다. 케이스 총수가
     * 49 → 42로 줄어도 이 비율은 그대로다(29%) — 줄어든 것은 같은 기능이 여러 벌 나오던 자리다. 저작이 그 자리에
     * 지어낸 문장 대신 케이스 번호를 넣을 수 있다.
     *
     * 나머지 전제는 **케이스가 아닌 기능**이 만든다 — 웨이브가 끝날 때 저절로 오르는 값 같은 것이라
     * 조작으로 지시할 수 없다. 그 자리는 `ScenarioPathService` 의 `Writer.Automatic` 이 "저절로
     * 일어난다"로 답한다(ARTEL-534). 두 답이 겹치지 않고 나뉘어 있어야 한다 — 저절로 되는 것을
     * 케이스로 시키면 실행이 멈춘다.
     */
    @Test
    fun `전제를 다른 케이스가 만들어 주는 자리가 있다`() {
        val changed = cases.flatMap { it.stateAfter.keys }.toSet()

        // 21 → 14(ARTEL-680) 남의 결과를 달고 있던 줄이 빠졌다.
        // 14 → 21(ARTEL-681) 관측이 들어오며 다시 늘었다 — 이번에는 제 주인이 든 것이다.
        assertThat(cases.count { case -> case.stateBefore.any { it.variable in changed } }).isEqualTo(21)
    }

    /**
     * **한쪽의 `state_after` 가 다른 쪽의 `state_before` 와 만난다.**
     *
     * 그것이 이 작업의 전부다 — 저작이 두 값을 맞추면 브리지가 되고, 지어낸 문장 대신 케이스
     * 번호가 들어간다. 실측에서 캐릭터가 걸어가는 자리가 그렇게 이어진다:
     *
     * ```
     * `RightArrow` 키를 누른다   →  state_after { position: +1 }
     * position == 1 인 상태에서  ←  state_before { position == 1 }
     * ```
     *
     * **이름이 마지막 마디로 통일되어 있어야 만난다.** 같은 값을 지도는 `MapMove.position`,
     * 사전조건은 `position` 으로 부른다.
     */
    @Test
    fun `바꾸는 값과 요구하는 값이 같은 이름으로 만난다`() {
        val changed = cases.flatMap { it.stateAfter.keys }.toSet()
        val required = cases.flatMap { it.stateBefore.map { guard -> guard.variable } }.toSet()

        assertThat(changed intersect required).isNotEmpty()
        // 걸어 다니는 자리가 실제로 이어지는지 — 이 개편이 풀려던 바로 그 케이스다.
        assertThat(changed).contains("position")
        assertThat(required).contains("position")
    }

    /**
     * **증감도 그대로 싣는다.**
     *
     * `+1` 은 값이 얼마가 되는지 말하지 않는다. 그래도 **어느 값이 어느 방향으로 움직이는지는**
     * 알고, 저작이 브리지를 고를 때 필요한 것이 그것이다. 확정값만 실으면 걸어 다니는 조작이
     * 통째로 빠진다 — 실측에서 그것이 대부분이다.
     */
    @Test
    fun `얼마가 되는지 모르는 증감도 버리지 않는다`() {
        assertThat(cases.flatMap { it.stateAfter.values }).contains("+1")
    }

    /**
     * **씬 전환도 상태 변화다**(ARTEL-614).
     *
     * 저작이 브리지를 고르려면 "이 케이스를 실행하면 어느 화면이 되나"를 알아야 한다. 지금까지 그
     * 답은 기대결과 **산문**에만 있었다 — 모델이 `` `Map_scene` 화면으로 전환된다`` 를 읽어 맞춰야
     * 했고, 그것이 이 개편이 없애려는 문자열 맞춤이다.
     *
     * `state_after` 에 실어 계약을 안 바꾼다. 키를 `scene` 으로 두는 것은 **다음 케이스의 `scene`
     * 칸과 같은 말**이라 맞추는 쪽이 헷갈리지 않기 때문이다.
     */
    @Test
    fun `실행하면 어느 화면이 되는지 말한다`() {
        val moves = cases.filter { it.stateAfter.containsKey("scene") }

        assertThat(moves).isNotEmpty()
        // 도착 화면은 실제로 있는 씬이어야 한다 — 지어낸 이름이면 저작이 갈 수 없는 곳을 가리킨다.
        val known = cases.map { it.scene }.toSet()
        assertThat(moves.mapNotNull { it.stateAfter["scene"] }.distinct()).isSubsetOf(known)
        // 기대결과가 전환을 말하는 케이스는 빠짐없이 도착 화면을 든다.
        assertThat(cases.count { it.expectedValue.contains("화면으로 전환된다") }).isEqualTo(moves.size)
    }

    /**
     * **지도가 아는 길을 저작이 받는다**(ARTEL-628).
     *
     * 실측에서 이 간선들이 지도에 앉아 있었는데 저작에는 한 번도 안 갔다:
     *
     * ```
     * Map_scene      → TurnBattleScene   Return
     * Map_scene      → TitleScene        Canvas/Button (Legacy)
     * TitleScene     → Map_scene         Canvas/MapSceneButton
     * GameClearScene → Map_scene         any
     * ```
     *
     * 길 찾기는 실행하는 쪽 몫이다. 그래도 **아는 것을 안 주는 것**과 안 찾아 주는 것은 다르다.
     */
    @Test
    fun `이 화면에서 어디로 어떻게 가는지 말한다`() {
        val exits = cases.associate { it.scene to it.exits }

        val toBattle = exits["Map_scene"].orEmpty().firstOrNull { it.scene == "TurnBattleScene" }
        assertThat(toBattle).isNotNull
        assertThat(toBattle!!.by).isEqualTo("Return")
        // 한 걸음이지 닫힘이 아니다 — 지도에서 엔딩으로 바로 가지는 않는다.
        assertThat(exits["Map_scene"].orEmpty().map { it.scene }).doesNotContain("EndingScene")
    }

    /**
     * **누를 것이 없는 것도 답이다.**
     *
     * 실측 19간선 중 12건이 `not-a-step` 이다 — 게임이 알아서 넘기는 자리라 실행하는 쪽이 버튼을
     * 찾아 헤맬 필요가 없다. `by` 를 비워 그렇게 말한다. 이것을 "모른다"와 섞으면, 있지도 않은
     * 조작을 찾다가 멎는다.
     */
    @Test
    fun `저절로 가는 자리는 누를 것을 비운다`() {
        val fromStory = cases.first { it.scene == "StoryScene" }.exits

        assertThat(fromStory).isNotEmpty
        assertThat(fromStory).anySatisfy { assertThat(it.by).isNull() }
    }

    /**
     * **어느 화면에서 움직이는 값인지 미리 말한다**(ARTEL-635).
     *
     * 전제는 서로 똑같이 생겼다. 한 줄로는 이 둘이 구별되지 않는다:
     *
     * ```
     * position == 0        방향키 한 번
     * StagePosition >= 1   전투를 이겨야 오른다
     * ```
     *
     * 그래서 실측(런 184)에서 저작이 스테이지를 안 깬 채로 지도를 활보하는 시나리오를 냈다 —
     * 첫 스텝이 `>= 1` 을 요구하는데 그 값을 올리는 전투 진입은 **마지막 스텝**이었다. 순환이고
     * 절대 실행되지 않는다. 거절하고 다시 쓰게 하는 것은 뒷수습이라, 짤 때 알려 준다.
     */
    @Test
    fun `그 값이 어느 화면에서 움직이는지 말한다`() {
        val raisedIn = cases.flatMap { it.stateBefore }
            .filter { it.raisedIn.isNotEmpty() }
            .associate { it.variable to it.raisedIn }

        // 전투를 이겨야 오르는 값과, 그 화면에서 방향키로 움직이는 값이 갈린다.
        assertThat(raisedIn["StagePosition"]).containsExactly("TurnBattleScene")
        assertThat(raisedIn["position"]).containsExactly("Map_scene")
        // 확정값(`0`)은 되돌리는 것이지 진행이 아니다. 타이틀이 모든 값의 출처가 되면 안 된다.
        assertThat(raisedIn.values.flatten()).doesNotContain("TitleScene")
    }

    companion object {
        private const val DOCUMENT = "src/test/resources/contentmap/wv-editor-latest.json"
    }
}
