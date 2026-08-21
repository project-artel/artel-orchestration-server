package kr.artel.orchestration.contentmap.ingest

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 적재가 실패하면 **문서에 사유가 남아야 한다.**
 *
 * 적재는 문서 하나가 한 트랜잭션이라 실패하면 통째로 되돌아간다. 기록을 그 안에서 쓰면 함께
 * 되돌아가고, 문서는 `ingested_at IS NULL` 인 채 **아무 일도 없었던 것과 똑같은 모양**이 된다 —
 * 사람이 화면에서 "왜 안 됐나"를 물을 자리가 사라진다.
 *
 * 실패는 결정적으로 만든다: 문서 행만 만들고 스토리지에는 객체를 넣지 않으면, 적재기가 바이트를
 * 못 찾아 그 자리에서 실패한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ContentMapIngestFailureRecordTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: ContentMapIngestService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var capabilities: CapabilityRepository

    /**
     * 버튼 경로(`ingestBuild`)가 실패를 문서에 적는다.
     *
     * 행은 하나도 안 남고, 도장도 안 찍히고, 사유만 남는다 — 다음 조회가 그 사유를 읽어 화면에 낸다.
     */
    @Test
    fun `적재 실패가 문서에 사유로 남는다`(): Unit = runBlocking {
        val build = newBuild()
        val map = newContentMap(build.id!!)
        val document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = map.id!!,
                // 스토리지에 넣지 않은 키. 적재기가 바이트를 못 찾는다.
                objectKey = "evidence/${System.nanoTime()}-missing.json",
                contentHash = "%064x".format(System.nanoTime()),
                byteSize = 10,
                receivedAt = Instant.now(),
            )
        )

        val outcomes = service.ingestBuild(build.id!!)

        val failed = outcomes.filterIsInstance<IngestOutcome.Failed>()
        assertThat(failed).hasSize(1)
        assertThat(failed.single().documentId).isEqualTo(document.id)

        val reloaded = documents.findById(document.id!!)!!
        assertThat(reloaded.ingestFailedAt).isNotNull()
        assertThat(reloaded.ingestError).isNotBlank()
        // 도장은 안 찍힌다. 다시 누르면 이 문서를 또 집는다.
        assertThat(reloaded.ingestedAt).isNull()
        assertThat(capabilities.findEvidenceCapabilitiesOfMap(map.id!!).toList()).isEmpty()
    }

    /**
     * 자동 경로(`ingestPending`)도 같은 기록을 남긴다.
     *
     * 두 입구가 루프 하나를 공유한다는 증거다. 갈라지면 자동 경로는 로그만 남기고 버튼 경로만
     * 기록하는, 같은 사고를 두 곳이 다르게 다루는 모양이 된다.
     */
    @Test
    fun `자동 경로도 같은 사유를 남긴다`(): Unit = runBlocking {
        val build = newBuild()
        val map = newContentMap(build.id!!)
        val document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = map.id!!,
                objectKey = "evidence/${System.nanoTime()}-missing.json",
                contentHash = "%064x".format(System.nanoTime()),
                byteSize = 10,
                receivedAt = Instant.now(),
            )
        )

        service.ingestPending(limit = 50)

        val reloaded = documents.findById(document.id!!)!!
        assertThat(reloaded.ingestFailedAt).isNotNull()
        assertThat(reloaded.ingestError).isNotBlank()
    }

    /**
     * 빌드 단위 조회가 다른 빌드의 대기 문서를 집지 않는다.
     *
     * 안 그러면 한 사람이 누른 버튼이 남의 프로젝트 문서를 자기 요청 시간에 적재하고, 그 실패가
     * 자기 응답에 섞인다.
     */
    @Test
    fun `다른 빌드의 대기 문서는 집지 않는다`(): Unit = runBlocking {
        val mine = newBuild()
        val other = newBuild()
        val myMap = newContentMap(mine.id!!)
        val otherMap = newContentMap(other.id!!)
        val myDocument = documents.save(pendingDocument(myMap.id!!))
        val otherDocument = documents.save(pendingDocument(otherMap.id!!))

        val outcomes = service.ingestBuild(mine.id!!)

        val touched = outcomes.map {
            when (it) {
                is IngestOutcome.Ingested -> it.result.documentId
                is IngestOutcome.Failed -> it.documentId
            }
        }
        assertThat(touched).containsExactly(myDocument.id)
        assertThat(documents.findById(otherDocument.id!!)!!.ingestFailedAt).isNull()
    }

    // ---------- 픽스처 ----------

    private fun pendingDocument(contentMapId: Long) = ContentMapDocumentEntity(
        contentMapId = contentMapId,
        objectKey = "evidence/${System.nanoTime()}-missing.json",
        contentHash = "%064x".format(System.nanoTime()),
        byteSize = 10,
        receivedAt = Instant.now(),
    )

    private suspend fun newBuild(): GameBuildEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "ingest-fail-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        return gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        )
    }

    private suspend fun newContentMap(gameBuildId: Long): ContentMapEntity =
        contentMaps.save(
            ContentMapEntity(
                gameBuildId = gameBuildId,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidenceDigest = "d4b31e4da9504b7d",
            )
        )
}
