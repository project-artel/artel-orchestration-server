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
     * 이 자리가 비어 있어서 모델이 브리지를 지어냈다. 실측 51건 중 **10건**이 값을 바꾼다 —
     * 나머지는 화면을 보거나 표시만 바꾸는 것이라 뒤에 남는 상태가 없다.
     *
     * 바뀌는 값은 셋이다: `position` · `stagePosition` · `flag`.
     */
    @Test
    fun `케이스가 무엇을 바꾸는지 말한다`() {
        assertThat(cases.count { it.stateAfter.isNotEmpty() }).isEqualTo(10)
        assertThat(cases.flatMap { it.stateAfter.keys }.distinct())
            .containsExactlyInAnyOrder("position", "stagePosition", "flag")
    }

    /**
     * **얼마나 이어지나.**
     *
     * 전제가 있는 케이스 중 **14건**이 다른 케이스가 만들어 주는 상태를 요구한다. 저작이 그 자리에
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

        assertThat(cases.count { case -> case.stateBefore.any { it.variable in changed } }).isEqualTo(14)
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

    companion object {
        private const val DOCUMENT = "src/test/resources/contentmap/wv-editor-latest.json"
    }
}
