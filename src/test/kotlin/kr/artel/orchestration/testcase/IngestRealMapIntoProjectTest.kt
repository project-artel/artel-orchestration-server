package kr.artel.orchestration.testcase

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.io.File

/**
 * **실제로 올린 지도를 실제 프로젝트에 앉힌다.** 새 생성기가 낸 케이스로 저작을 돌려 보려는 것뿐이다.
 *
 * SDK 는 근거 문서를 올리고 등록까지 하지만, 적재는 스캔 결과 프레임이 와야 돈다(`ScanResultRouter`).
 * 유니티 없이 그 프레임을 만들 수 없어 적재만 여기서 부른다 — 적재 자체는 SDK 가 타는 그 경로다.
 *
 * 켤 때만 돈다. `ARTEL_INGEST_DOC`(문서 id)와 `ARTEL_INGEST_BYTES`(그 문서의 파일)를 주면 그때
 * 한 번 적재한다. 안 주면 아무것도 하지 않는다 — 평소 테스트 판에 실제 프로젝트를 만들지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest
class IngestRealMapIntoProjectTest {

    @TestConfiguration
    class FakeStorageConfig {
        /** 바이트는 파일에서 읽어 넣는다. MinIO 자격을 테스트 판에 들고 오지 않으려는 것이다. */
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var testCases: TestCaseRepository

    @Test
    fun `올린 문서를 적재한다`() = runBlocking {
        val documentId = System.getenv("ARTEL_INGEST_DOC")?.toLongOrNull() ?: return@runBlocking
        val bytesFrom = System.getenv("ARTEL_INGEST_BYTES") ?: return@runBlocking

        val all = documents.findAll().toList()
        println("보이는 문서 ${all.size}개: ${all.map { it.id }}")
        val document = documents.findById(documentId) ?: error("문서 $documentId 이 없다")
        (storage as FakeDocumentStorage).put(document.objectKey, File(bytesFrom).readBytes())

        val result = ingest.ingest(document)
        println("적재 결과: $result")

        val projectId = System.getenv("ARTEL_INGEST_PROJECT")?.toLongOrNull() ?: return@runBlocking
        val rows = testCases.findByProjectIdOrderByIdAsc(projectId).toList()
        println("프로젝트 $projectId 케이스 ${rows.size}건")
    }
}
