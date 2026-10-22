package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
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
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.config.MapTestCaseGeneratorProperties.Grouping
import kr.artel.orchestration.testcase.generator.MapTestCase
import kr.artel.orchestration.testcase.generator.MapTestCaseGenerator
import kr.artel.orchestration.testcase.generator.MapTestCaseReadingRules
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
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
 * **옛 묶기와 판독 기준 묶기를 같은 근거 문서로 견준다**(ARTEL-1209, POC).
 *
 * 평소에는 건너뛴다. 문서가 크고(1.4.0 은 5.6 MB) 저장소에 넣지 않기 때문이다. 재려면:
 *
 * ```
 * ./mvnw test -Dtest=MapTestCaseGroupingMeasureTest \
 *   -Dtc.measure.docs=/path/wv-1.0.json,/path/wv-1.4.0.json -Dtc.measure.out=/path/out
 * ```
 *
 * 문서마다 지도를 새로 적재하고 두 방식으로 생성해, 지표를 한 줄씩 찍고 TC 를 JSON 으로 남긴다.
 */
@ActiveProfiles("test")
@SpringBootTest
class MapTestCaseGroupingMeasureTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var generator: MapTestCaseGenerator
    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var scenes: kr.artel.orchestration.contentmap.repository.SceneRepository

    @Test
    fun `두 묶기를 같은 문서로 견준다`(): Unit = runBlocking {
        val docs = System.getProperty("tc.measure.docs")
        assumeTrue(!docs.isNullOrBlank(), "tc.measure.docs 가 없어 건너뛴다")
        val out = File(System.getProperty("tc.measure.out") ?: "target/tc-measure").apply { mkdirs() }

        val lines = mutableListOf("문서 | 방식 | TC | 씬 빼고 서로 다른 TC | 성립 불가 사전조건 | 판독 못 하는 기대 항목 | 판독 못 하는 사전조건 비교 | 준비할 상태 있음 | 어느 씬에서나 | 볼 결과 없음 | 사전조건 비교 5+ | 토글 든 TC")
        for (path in docs!!.split(',').map { it.trim() }) {
            val mapId = load(File(path))
            val names = contentMaps.findSceneObjectNames(mapId).toList()
                .groupBy({ it.sceneName }, { it.path }).mapValues { (_, v) -> v.toSet() }
            for (grouping in Grouping.entries) {
                val cases = generator.generate(mapId, grouping)
                lines += row(File(path).name, grouping, cases, names)
                File(out, "${File(path).nameWithoutExtension}-${grouping.name.lowercase()}.json")
                    .writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cases))
            }
        }
        File(out, "summary.txt").writeText(lines.joinToString("\n"))
        println(lines.joinToString("\n"))
    }

    private fun row(doc: String, grouping: Grouping, cases: List<MapTestCase>, names: Map<String, Set<String>>): String {
        val distinct = cases.map { listOf(it.step.replace(it.scene, "#"), it.precondition.removePrefix(it.scene), it.expected) }.toSet().size
        val impossible = cases.count { MapTestCaseReadingRules.impossible(it.condition, it.scene) }
        val unreadable = cases.sumOf { case ->
            case.expectedChecks.count { !MapTestCaseReadingRules.collectable(it, names[case.scene].orEmpty()) }
        }
        val withSetup = cases.count { it.setup.isNotEmpty() }
        val anyScene = cases.count { it.step.endsWith(MapTestCaseReadingRules.ANY_SCENE) }
        val nothing = cases.count { it.expected == MapTestCaseReadingRules.NO_READABLE_RESULT }
        val unreadablePre = cases.count { it.precondition.contains("(확인 불가)") }
        val longPre = cases.count { it.precondition.split("그리고").size >= 5 }
        val toggled = cases.count { it.toggles.isNotEmpty() }
        return "$doc | ${grouping.name.lowercase()} | ${cases.size} | $distinct | $impossible | $unreadable | $unreadablePre | $withSetup | $anyScene | $nothing | $longPre | $toggled"
    }

    /** 문서 하나를 새 프로젝트 · 빌드 · 지도로 적재한다. 다른 측정과 섞이지 않게 매번 새로 만든다. */
    private suspend fun load(file: File): Long {
        val now = Instant.now()
        val project = projects.save(ProjectEntity(name = "measure-${file.name}", genre = "RPG", createdAt = now, updatedAt = now))!!
        val build = gameBuilds.save(GameBuildEntity(projectId = project.id!!, version = file.nameWithoutExtension, createdAt = now, updatedAt = now))
        val map = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 7, capture = Capture.EDITOR_PLAY.wire,
                evidencePromises = Json.of("""["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""),
                evidenceDigest = "measure", unity = "2022.3.62f3", backend = "mono", development = true, sdkVersion = "0.1.0",
            )
        )
        val bytes = file.readBytes()
        val key = "content-map/${map.id}/${file.name}"
        (storage as FakeDocumentStorage).put(key, bytes)
        ingest.ingest(
            documents.save(
                ContentMapDocumentEntity(
                    contentMapId = map.id!!, objectKey = key,
                    contentHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                    byteSize = bytes.size.toLong(),
                )
            )
        )
        // 실제 빌드는 씬 목록(`sceneScan`)으로 입구 씬을 표시한다. 여기엔 빌드 스캔이 없어서 문서의 첫 씬을 입구로 둔다.
        val first = objectMapper.readTree(bytes).path("scenes").path(0).asText(null)
        scenes.findByContentMapIdOrderByNameAsc(map.id!!).toList().firstOrNull { it.name == first }
            ?.let { scenes.save(it.copy(isEntry = true)) }
        return map.id!!
    }
}
