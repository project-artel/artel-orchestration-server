package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.ingest.IngestResult
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
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
 * 적재가 케이스를 **실제로 앉히는지**를 실측 문서로 확인한다(ARTEL-578).
 *
 * `MapTestCaseGeneratorGoldenTest` 는 생성기가 무엇을 내는지를 본다. 여기는 그것이 `test_case` 에
 * 앉아 저작이 읽을 수 있게 되는지를 본다 — 그 자리가 끊겨 있어서 개편 전체가 저작에 안 닿았다.
 *
 * **같은 문서를 두 번 적재한다.** 두 번째가 표를 부풀리면 겹침 판정이 같은 케이스를 못 알아본 것이고,
 * 그것은 SDK 가 재등록할 때마다 케이스가 배로 늘어난다는 뜻이다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// **하나의 적재 결과를 함께 본다.** 문서를 앉히는 데 몇 초가 걸려 시험마다 새로 앉히지 않는다.
// 그래서 표를 바꾸는 시험은 순서를 못 박는다 — 지도가 안 내는 키로 바꿔 버리는 마지막 시험이
// 먼저 돌면, 그 뒤의 시험들이 자기가 만들지 않은 상태를 본다.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MapTestCaseWriterGoldenTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var projectId: Long = 0
    private var contentMapId: Long = 0
    private lateinit var first: IngestResult
    private lateinit var second: IngestResult
    private lateinit var rows: List<TestCaseEntity>
    private lateinit var document: ContentMapDocumentEntity

    @BeforeAll
    fun ingestTwice() = runBlocking {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "writer-golden", genre = "RPG", createdAt = now, updatedAt = now)
        )
        projectId = project.id!!
        val build = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "gen", createdAt = now, updatedAt = now)
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
        contentMapId = map.id!!

        // 손으로 쓴 케이스가 이미 있다. 갈아 끼울 때 이것이 살아남아야 한다.
        testCases.save(
            TestCaseEntity(
                projectId = projectId, scene = "Map_scene", step = "손으로 쓴 스텝",
                precondition = "사람이 적은 전제", expectedValue = "사람이 적은 결과",
            )
        )

        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/$contentMapId/wv-editor-latest.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)
        document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId, objectKey = objectKey,
                contentHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                byteSize = bytes.size.toLong(),
            )
        )

        first = ingestOnce()
        second = ingestOnce()
        rows = testCases.findByProjectIdOrderByIdAsc(projectId).toList()
    }

    /**
     * 같은 문서 행을 다시 적재한다. 해시가 유일 제약이라 두 번째 행을 만들 수 없고, 실제 재적재도
     * 그 모양이다 — SDK 가 같은 빌드를 다시 올리면 문서는 하나고 적재만 다시 돈다.
     */
    private suspend fun ingestOnce(): IngestResult = ingest.ingest(document)

    private fun mine() = rows.filter {
        objectMapper.readTree(it.metadata.asString()).path("origin").asText() == MapTestCaseWriter.ORIGIN
    }

    /**
     * **적재가 케이스를 앉힌다.**
     *
     * 이 자리가 끊겨 있었다. 생성기를 부르는 곳이 골든 테스트뿐이라, 저작은 여전히 구버전이 엑셀로
     * 넣은 줄을 읽었다.
     *
     * 생성기가 **49건**을 낸다(ARTEL-616 뒤). 여기서 더 접히는 것은 없다 — 생성기가 이미
     * 씬·조작·결과로 묶었고, 이 자리의 겹침 판정도 같은 잣대라 두 번 접힐 일이 없다.
     *
     * 접히는 자리가 남아 있는 것은 **서로 다른 지도에서 온 케이스**를 위해서다. 그때 접힌 줄의
     * `capability_key` 는 먼저 온 기능의 것이라 되짚기가 한쪽만 가리킨다.
     */
    @Test
    fun `적재가 지도의 케이스를 앉힌다`() {
        assertThat(mine()).isNotEmpty()
        // 46 → 31(ARTEL-680) 생명주기 아래 형제에게서 결과를 빌려 오지 않는다.
        // 31 → 80(ARTEL-681) 게임이 스스로 하는 일도 케이스가 된다.
        assertThat(mine()).hasSize(89)
        assertThat(first.testCases.created).isEqualTo(89)
    }

    /**
     * **문장이 바뀌어도 같은 줄로 알아본다**(ARTEL-617).
     *
     * 앞서 정체가 사용자에게 보이는 네 칸이라, 문장 규칙을 고칠 때마다 옛 줄이 사라진 것으로
     * 판정되고 그것을 인용한 시나리오가 통째로 상했다 — 실측에서 규칙을 다섯 번 고치자 케이스
     * 18건과 시나리오 3개가 `BROKEN` 이 됐다.
     *
     * 지도 키와 그 케이스를 낸 효과로 잡으면 문구가 좋아져도 같은 줄이다.
     */
    @Test
    fun `앉은 케이스가 문장과 무관한 정체를 든다`() {
        assertThat(mine()).allSatisfy { row ->
            val key = objectMapper.readTree(row.metadata.asString()).path(MapTestCaseWriter.CASE_KEY).asText()
            assertThat(key).isNotBlank()
            // 지도 키로 시작한다 — 문장이 아니라 지도가 정하는 값이라는 뜻이다.
            assertThat(key).startsWith(row.capabilityKey)
        }
        // 정체가 겹치면 한 줄이 다른 줄을 덮어써 조용히 사라진다.
        assertThat(mine().map { objectMapper.readTree(it.metadata.asString()).path(MapTestCaseWriter.CASE_KEY).asText() })
            .doesNotHaveDuplicates()
    }

    /**
     * **두 번 적재해도 표가 부풀지 않는다.**
     *
     * SDK 는 재등록마다 같은 문서를 다시 올린다. 겹침 판정이 없으면 그때마다 케이스가 배로 늘고,
     * 저작 프롬프트에 같은 시험이 여러 번 실린다.
     */
    @Test
    fun `같은 문서를 다시 적재해도 케이스가 늘지 않는다`() {
        assertThat(second.testCases.created).isZero()
        assertThat(second.testCases.deleted).isZero()
        assertThat(second.testCases.broken).isZero()
        // **한 행도 다시 쓰지 않는다.** `metadata` 는 `jsonb` 라 키 순서가 정규화되어 저장되므로
        // 글자로 견주면 늘 다르게 나오고, 그러면 아무것도 안 바뀐 적재가 매번 전량을 다시 쓴다.
        // 헛도는 쓰기보다 나쁜 것은 그 수가 "49행이 달라졌다"고 거짓말하는 것이다.
        assertThat(second.testCases.updated).isZero()
    }

    /**
     * **`capability_key` 가 채워진다.**
     *
     * 이것이 없으면 ARTEL-553 이 만든 칸도, ARTEL-555 가 그 키로 지도를 되짚는 길도 한 번도 안 쓰인다.
     * 되짚기가 문자열 맞춤으로 되돌아가는 자리다.
     */
    @Test
    fun `앉은 케이스가 지도를 되짚을 키를 든다`() {
        assertThat(mine()).allSatisfy { assertThat(it.capabilityKey).isNotBlank() }
    }

    /**
     * **남의 행을 건드리지 않는다.**
     *
     * 손으로 쓴 케이스와 엑셀로 들어온 케이스는 출처 표시가 없다. 두 경로가 한동안 공존하는 것이
     * 되돌아갈 길이므로(ARTEL-556 의 대조가 통과하기 전까지) 그 경계가 곧 안전장치다.
     */
    @Test
    fun `손으로 쓴 케이스는 살아남는다`() {
        assertThat(rows.map { it.step }).contains("손으로 쓴 스텝")
        assertThat(mine().map { it.step }).doesNotContain("손으로 쓴 스텝")
    }

    /**
     * **문장이 바뀌어도 사라지지 않는다**(ARTEL-617).
     *
     * 이것이 정체를 지도 키로 옮긴 이유다. 앞서는 문구를 고치면 옛 줄이 사라진 것으로 판정되어,
     * 그것을 인용한 시나리오가 통째로 상했다 — 실측에서 규칙을 다섯 번 고치자 18건이 `BROKEN` 이
     * 됐다. 문구가 좋아지는 것이 사용자의 저작물을 깨뜨릴 이유는 없다.
     */
    @Test
    @Order(1)
    fun `문장을 고쳐도 같은 줄로 알아본다`(): Unit = runBlocking {
        val one = testCases.findById(mine().first().id!!)!!
        testCases.save(one.copy(step = "사람이 손으로 고친 문구", expectedValue = "고친 기대결과"))

        val after = ingestOnce()

        // 새로 만들지도, 지우지도 않는다 — 같은 줄을 찾아 문구만 되돌린다.
        assertThat(after.testCases.created).isZero()
        assertThat(after.testCases.deleted).isZero()
        assertThat(after.testCases.broken).isZero()
        assertThat(testCases.findById(one.id!!)?.step).isEqualTo(one.step)
    }

    /**
     * **시나리오가 든 케이스는 지우지 않는다.**
     *
     * 시나리오는 스텝 안에 `case_id` 를 숫자로 든다 — 외래 키가 아니라 지워도 DB 가 막지 않고,
     * 시나리오에 가리키는 것이 없는 번호만 남는다. 그래서 지우는 대신 `BROKEN` 으로 돌린다.
     */
    @Test
    @Order(2)
    fun `시나리오가 인용한 케이스는 지우지 않고 상했다고 표시한다`(): Unit = runBlocking {
        val cited = mine().first()
        scenarios.save(
            TestScenarioEntity(
                projectId = projectId, title = "인용", description = "",
                steps = Json.of("""[{"action":"확인","case_id":${cited.id}}]"""),
            )
        )
        // **지도에서 사라진 것처럼 만든다.** 키로도 문장으로도 안 잡혀야 사라진 것이다 —
        // 둘 중 하나만 바꾸면 나머지 하나가 같은 줄이라고 알아본다(ARTEL-617).
        testCases.save(
            cited.copy(
                step = "지도가 더는 말하지 않는 스텝",
                metadata = Json.of(
                    objectMapper.writeValueAsString(
                        objectMapper.readTree(cited.metadata.asString()).deepCopy<ObjectNode>()
                            .put(MapTestCaseWriter.CASE_KEY, "지도가 더는 내지 않는 키")
                    )
                )
            )
        )

        val after = ingestOnce()

        assertThat(after.testCases.broken).isEqualTo(1)
        assertThat(testCases.findById(cited.id!!)?.verificationStatus).isEqualTo("BROKEN")
    }

    /**
     * **전제가 구조로 앉는다**(ARTEL-627).
     *
     * `precondition` 문자열은 사람에게 보여줄 한 줄이고, 되짚을 것은 이쪽이다. 앞서는 이 칸이 없어
     * 소비하는 쪽이 문장을 정규식으로 되읽었고, 거기서 대상의 주인(`A.b.activeSelf` → `activeSelf`),
     * 갈래(`또는`), 식(`(x - 1)`)이 사라졌다.
     *
     * **이 수가 0이 되면 새 생성기를 만든 이유가 사라진 것이다** — 문자열 맞춤을 없애려고 만들었는데
     * 그 아래 표에서 다시 문자열이 된다.
     */
    @Test
    fun `케이스가 전제를 구조로 든다`(): Unit = runBlocking {
        val rows = mine()
        val withTree = rows.filter { it.condition != null }

        assertThat(withTree).isNotEmpty()
        // 전제가 있는 케이스는 빠짐없이 트리를 든다. 문장만 있고 구조가 없는 줄이 남으면
        // 그 줄에서 소비하는 쪽이 다시 문장을 파싱한다.
        assertThat(rows.filter { it.precondition?.contains(" / ") == true })
            .allSatisfy { assertThat(it.condition).isNotNull() }
        // 트리는 지도 조회 API 와 같은 표현이다. 표현을 두 벌 만들면 읽는 쪽이 어느 쪽인지 물어야 한다.
        assertThat(withTree.first().condition!!.asString()).contains("\"kind\"")
    }

    companion object {
        private const val DOCUMENT = "src/test/resources/contentmap/wv-editor-latest.json"
    }
}
