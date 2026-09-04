package kr.artel.orchestration.testscenario

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
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
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix
import kr.artel.orchestration.testscenario.service.ScenarioFlowPlan
import kr.artel.orchestration.testscenario.service.ScenarioFlowPlanner
import kotlinx.coroutines.flow.toList
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
 * **흐름이 무엇으로 나오는지 글자로 굳혀 둔다.**
 *
 * 짝 행렬은 케이스 수의 제곱만큼 길찾기를 부른다 — 88건이면 7,656칸에 11초다. 그 수를 줄이는
 * 일이 예정돼 있는데, 줄이고 나서 **답이 그대로인지**를 이 파일이 답한다.
 *
 * 저작 agent 를 부르지 않는다. 흐름은 지도와 케이스만으로 정해지므로 모델 없이 재현된다 —
 * 실비를 쓰지 않고 견줄 수 있는 것이 이 검사의 값어치다.
 *
 * 골든을 다시 뜨려면 `-DwriteGolden=true` 로 돌린다. 값이 달라졌다면 **왜 달라져도 되는지를
 * 먼저 말할 수 있어야** 한다 — 흐름이 바뀌면 저작이 짜는 순서가 바뀐다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioFlowGoldenTest {

    @TestConfiguration
    class Storage {
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
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var flowMatrix: ScenarioFlowMatrix
    @Autowired private lateinit var flowPlanner: ScenarioFlowPlanner

    private var projectId: Long = 0
    private var userId: Long = 0
    private lateinit var caseIds: List<Long>
    private lateinit var matrix: ScenarioFlowMatrix.Matrix
    private lateinit var rendered: String

    @BeforeAll
    fun ingestAndPlan() = runBlocking {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "flow-golden", genre = "RPG", createdAt = now, updatedAt = now)
        )
        projectId = project.id!!
        userId = users.save(testAppUser("flow-golden tester", now)).id!!
        members.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "gen", createdAt = now, updatedAt = now)
        )
        val map = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 7, capture = Capture.EDITOR_PLAY.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3", backend = "mono", development = true, sdkVersion = "0.1.0",
            )
        )
        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/${map.id}/wv-play-2026-09-01.json"
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
        caseIds = testCases.findIdsByProjectId(projectId).toList().sorted()
        matrix = flowMatrix.of(projectId, userId, caseIds)
        val flows = flowPlanner.of(projectId, userId, matrix)
        rendered = render(flows)

        if (System.getProperty("writeGolden") == "true") {
            File(GOLDEN).writeText(rendered)
        }
    }

    /**
     * **흐름이 골든과 같은 글자다.**
     *
     * 케이스 번호는 적재 순서에 따라 달라지므로 자리(씬·스텝 앞머리)로 적는다 — 같은 지도를
     * 다시 적재해도 같은 글자가 나와야 이 검사가 뜻을 갖는다.
     */
    @Test
    fun `흐름이 골든과 같다`() {
        val golden = File(GOLDEN)
        assertThat(golden).exists()
        assertThat(rendered).isEqualTo(golden.readText())
    }

    /**
     * **짝 행렬이 케이스 수의 제곱이다.** 줄이는 일의 출발점을 수로 남긴다 — 이 값이 떨어지면
     * 그만큼 길찾기를 덜 부른 것이고, 위의 검사가 답이 그대로임을 함께 말한다.
     */
    @Test
    fun `행렬은 아직 모든 짝을 푼다`() {
        val n = caseIds.size
        assertThat(n).isEqualTo(88)
        val solved = ScenarioFlowMatrix.Link.entries.sumOf { matrix.count(it) }
        assertThat(solved).isEqualTo(n * (n - 1))
    }

    private fun render(flows: List<ScenarioFlowPlan.Flow>): String = buildString {
        appendLine("흐름 ${flows.size}개 · 케이스 ${caseIds.size}건")
        flows.forEachIndexed { index, flow ->
            appendLine()
            appendLine("흐름 ${index + 1} — 스텝 ${flow.caseIds.size} · 지나갈 자리 ${flow.gaps}")
            appendLine(
                "  시작 조건: " + flow.opening
                    .joinToString(", ") { "${it.variable} ${it.operator} ${it.value}" }
                    .ifBlank { "없음" }
            )
            flow.caseIds.forEach { id ->
                val cell = flow.caseIds.getOrNull(flow.caseIds.indexOf(id) - 1)
                    ?.let { matrix.between(it, id) }
                appendLine("    ${place(id)}${cell?.let { "  ← ${it.link}" } ?: ""}")
            }
        }
    }

    /** 케이스를 번호가 아니라 **자리**로 적는다. 번호는 적재 순서를 타서 골든이 못 된다. */
    private fun place(id: Long): String = runBlocking {
        val case = testCases.findById(id) ?: return@runBlocking "?$id"
        val head = case.step.take(46).replace('\n', ' ')
        "${case.scene} · $head"
    }

    private companion object {
        const val DOCUMENT = "src/test/resources/contentmap/wv-play-2026-09-01.json"
        const val GOLDEN = "src/test/resources/contentmap/golden-flows.txt"
    }
}
