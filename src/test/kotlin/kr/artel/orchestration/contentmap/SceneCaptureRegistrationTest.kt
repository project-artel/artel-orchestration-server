package kr.artel.orchestration.contentmap

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketRequest
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentRequest
import kr.artel.orchestration.contentmap.dto.SceneCaptureRegistration
import kr.artel.orchestration.contentmap.dto.SceneCaptureTicketBatchRequest
import kr.artel.orchestration.contentmap.dto.SceneCaptureTicketRequest
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.ContentMapSceneCaptureRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.service.EvidenceDocumentHeaderReader
import kr.artel.orchestration.contentmap.service.EvidenceDocumentService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 씬 대표 이미지가 근거 등록에 실려 오는 경로를 검증한다.
 *
 * 이 경로가 조용히 틀리면 **화면이 거짓말을 한다** — 남의 빌드 이미지가 자기 씬에 붙거나, 지난
 * 실행에서 찍힌 낡은 이미지가 이번 실행의 실패 옆에 남는다. 그래서 보는 것은 왕복이 아니라 판정이다:
 *
 * 1. 신고한 키가 정말 이 빌드에 올라온 것인가
 * 2. 성공과 실패가 섞인 반쪽 행을 거절하는가
 * 3. 재등록이 덧붙이지 않고 갈아 끼우는가
 * 4. 캡처를 아예 안 보내는 옛 SDK 가 그대로 통하는가
 */
@ActiveProfiles("test")
@SpringBootTest
class SceneCaptureRegistrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: EvidenceDocumentService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var sceneCaptures: ContentMapSceneCaptureRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    /** 헤더만 읽히면 되므로 씬 목록은 짧게 둔다. 잘린 JSON 경로는 `EvidenceDocumentServiceTest` 가 본다. */
    private fun evidenceDocument(digest: String = "d4b31e4da9504b7d"): ByteArray {
        val scenes = (1..2_000).joinToString(",") { "\"Scene$it\"" }
        val bytes = """
            {
              "schema": 6,
              "capture": "${Capture.EDITOR.wire}",
              "capabilities": ["build-info-v1"],
              "build": {
                "unity": "2022.3.62f3",
                "platform": "OSXEditor",
                "backend": "mono",
                "development": true,
                "sdk": "0.1.0",
                "evidence": "$digest"
              },
              "scenes": [$scenes],
              "types": {},
              "objects": []
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        check(bytes.size > EvidenceDocumentHeaderReader.PREFIX_BYTES)
        return bytes
    }

    private suspend fun seed(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = db.sql("INSERT INTO app_user (display_name) VALUES ('sdk') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
        val project = projects.save(
            ProjectEntity(name = "capture-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(
                projectId = project.id,
                version = "v${System.nanoTime()}",
                createdAt = now,
                updatedAt = now,
            )
        )
        return userId to build.id!!
    }

    private suspend fun uploadEvidence(userId: Long, buildId: Long, bytes: ByteArray): String {
        val ticket = service.createUploadTicket(userId, buildId, EvidenceUploadTicketRequest(bytes.size.toLong()))!!
        fakeStorage.put(ticket.objectKey, bytes)
        return ticket.objectKey
    }

    /** 티켓을 받아 그 키에 이미지를 올린, 즉 SDK 가 정상 경로를 지난 상태를 만든다. */
    private suspend fun uploadCapture(userId: Long, buildId: Long, sceneName: String): String {
        val batch = service.createSceneCaptureTickets(
            userId,
            buildId,
            SceneCaptureTicketBatchRequest(
                listOf(
                    SceneCaptureTicketRequest(
                        sceneName = sceneName,
                        contentType = "image/jpeg",
                        contentLength = 1_024,
                        width = 320,
                        height = 180,
                    )
                )
            ),
        )!!
        val ticket = batch.captures.single()
        storage.put(ticket.objectKey, ByteArray(1_024), "image/jpeg").block()
        return ticket.objectKey
    }

    private fun success(sceneName: String, objectKey: String) = SceneCaptureRegistration(
        sceneName = sceneName,
        objectKey = objectKey,
        contentType = "image/jpeg",
        width = 320,
        height = 180,
    )

    /**
     * 티켓 키에 `gameBuildId` 가 박힌다. 등록이 그 접두사를 다시 확인하므로, 이 규칙이 깨지면
     * 아래 `남의 빌드에 올라간 키는 붙지 않는다` 가 지키는 경계도 함께 무너진다.
     */
    @Test
    fun `씬마다 티켓을 한 번에 낸다`(): Unit = runBlocking {
        val (userId, buildId) = seed()

        val batch = service.createSceneCaptureTickets(
            userId,
            buildId,
            SceneCaptureTicketBatchRequest(
                listOf("TitleScene", "MapScene").map {
                    SceneCaptureTicketRequest(it, "image/jpeg", 1_024, 320, 180)
                }
            ),
        )!!

        assertThat(batch.captures).hasSize(2)
        assertThat(batch.captures.map { it.sceneName }).containsExactly("TitleScene", "MapScene")
        assertThat(batch.captures).allSatisfy {
            assertThat(it.objectKey).startsWith("content-map-scene-captures/$buildId/")
            assertThat(it.requiredHeaders["Content-Type"]).isEqualTo("image/jpeg")
        }
        // 키는 씬마다 다르다 — 같으면 뒤에 올린 씬이 앞 씬을 덮어쓴다
        assertThat(batch.captures.map { it.objectKey }.distinct()).hasSize(2)
    }

    /** 씬 하나에 대표 이미지는 한 장이다. 두 장을 받으면 어느 쪽이 대표인지 서버가 정할 수 없다. */
    @Test
    fun `같은 씬을 두 번 신고하면 티켓을 내지 않는다`(): Unit = runBlocking {
        val (userId, buildId) = seed()

        assertThatThrownBy {
            runBlocking {
                service.createSceneCaptureTickets(
                    userId,
                    buildId,
                    SceneCaptureTicketBatchRequest(
                        listOf(
                            SceneCaptureTicketRequest("TitleScene", "image/jpeg", 1_024, 320, 180),
                            SceneCaptureTicketRequest("TitleScene", "image/jpeg", 1_024, 320, 180),
                        )
                    ),
                )
            }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /** JPEG 만 받는다. 형식을 열어 두면 화면이 무엇을 그릴 수 있는지 알 수 없다. */
    @Test
    fun `JPEG 이 아니면 티켓을 내지 않는다`(): Unit = runBlocking {
        val (userId, buildId) = seed()

        assertThatThrownBy {
            runBlocking {
                service.createSceneCaptureTickets(
                    userId,
                    buildId,
                    SceneCaptureTicketBatchRequest(
                        listOf(SceneCaptureTicketRequest("TitleScene", "image/png", 1_024, 320, 180))
                    ),
                )
            }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /** 남의 빌드는 없는 것과 같다 — 부재와 권한 없음을 구분해 주면 id 를 훑을 수 있다. */
    @Test
    fun `남의 빌드에는 티켓을 내지 않는다`(): Unit = runBlocking {
        val (_, buildId) = seed()
        val stranger = db.sql("INSERT INTO app_user (display_name) VALUES ('stranger') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

        val batch = service.createSceneCaptureTickets(
            stranger,
            buildId,
            SceneCaptureTicketBatchRequest(listOf(SceneCaptureTicketRequest("TitleScene", "image/jpeg", 1, 1, 1))),
        )

        assertThat(batch).isNull()
    }

    /** 성공한 캡처와 못 찍은 이유가 같은 등록에 함께 온다. 둘 다 화면이 알아야 하는 사실이다. */
    @Test
    fun `성공과 실패를 함께 적는다`(): Unit = runBlocking {
        val (userId, buildId) = seed()
        val objectKey = uploadCapture(userId, buildId, "TitleScene")

        val result = service.register(
            userId,
            buildId,
            RegisterEvidenceDocumentRequest(
                objectKey = uploadEvidence(userId, buildId, evidenceDocument()),
                sceneCaptures = listOf(
                    success("TitleScene", objectKey),
                    SceneCaptureRegistration(sceneName = "MapScene", failureCode = "unsupported-render-pipeline"),
                ),
            ),
        )!!

        val rows = sceneCaptures.findByDocumentIdOrderBySceneNameAsc(result.documentId).toList()
        assertThat(rows).hasSize(2)
        with(rows.single { it.sceneName == "TitleScene" }) {
            assertThat(this.objectKey).isEqualTo(objectKey)
            assertThat(width).isEqualTo(320)
            assertThat(height).isEqualTo(180)
            assertThat(failureCode).isNull()
        }
        with(rows.single { it.sceneName == "MapScene" }) {
            assertThat(this.objectKey).isNull()
            assertThat(failureCode).isEqualTo("unsupported-render-pipeline")
        }
    }

    /**
     * 성공과 실패를 섞은 항목은 거절한다.
     *
     * 반쯤 찬 행을 받아 두면 화면이 무엇을 믿을지 정할 수 없다 — 이미지가 있는데 실패 이유도 있는
     * 씬은 그린다는 뜻인가 못 그린다는 뜻인가.
     */
    @Test
    fun `성공과 실패를 섞은 캡처를 거절한다`(): Unit = runBlocking {
        val (userId, buildId) = seed()
        val objectKey = uploadCapture(userId, buildId, "TitleScene")

        assertThatThrownBy {
            runBlocking {
                service.register(
                    userId,
                    buildId,
                    RegisterEvidenceDocumentRequest(
                        objectKey = uploadEvidence(userId, buildId, evidenceDocument()),
                        sceneCaptures = listOf(
                            success("TitleScene", objectKey).copy(failureCode = "camera-missing")
                        ),
                    ),
                )
            }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /**
     * 남의 빌드에 올라간 키는 붙지 않는다.
     *
     * 붙는다면 빌드 id 를 바꿔 가며 등록하는 것만으로 남의 게임 화면을 자기 Content Map 에서 볼 수 있다.
     */
    @Test
    fun `다른 빌드의 캡처 키는 붙지 않는다`(): Unit = runBlocking {
        val (userId, buildId) = seed()
        val (otherUserId, otherBuildId) = seed()
        val foreignKey = uploadCapture(otherUserId, otherBuildId, "TitleScene")

        assertThatThrownBy {
            runBlocking {
                service.register(
                    userId,
                    buildId,
                    RegisterEvidenceDocumentRequest(
                        objectKey = uploadEvidence(userId, buildId, evidenceDocument()),
                        sceneCaptures = listOf(success("TitleScene", foreignKey)),
                    ),
                )
            }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /** 신고만 하고 올리지 않은 키는 없는 객체다. 신고 값만 믿으면 빈 이미지를 가리키는 행이 앉는다. */
    @Test
    fun `올라오지 않은 캡처 키를 거절한다`(): Unit = runBlocking {
        val (userId, buildId) = seed()
        val neverUploaded = "content-map-scene-captures/$buildId/00000000-0000-0000-0000-000000000000.jpg"

        assertThatThrownBy {
            runBlocking {
                service.register(
                    userId,
                    buildId,
                    RegisterEvidenceDocumentRequest(
                        objectKey = uploadEvidence(userId, buildId, evidenceDocument()),
                        sceneCaptures = listOf(success("TitleScene", neverUploaded)),
                    ),
                )
            }
        }.isInstanceOf(NotFoundException::class.java)
    }

    /**
     * 재등록이 캡처를 갈아 끼운다.
     *
     * SDK 는 실행마다 등록하고, 실행마다 캡처에 성공한 씬 집합이 달라진다. 덧붙이면 지난 실행의
     * 낡은 이미지가 이번 실행의 실패 옆에 남아 못 찍은 씬을 찍은 것처럼 보인다. 같은 문서를 다시
     * 보내는 경로(중복 digest)에서도 그래야 한다 — SDK 는 코드가 안 바뀌면 같은 문서를 보낸다.
     */
    @Test
    fun `다시 등록하면 캡처를 갈아 끼운다`(): Unit = runBlocking {
        val (userId, buildId) = seed()
        val bytes = evidenceDocument()
        val first = service.register(
            userId,
            buildId,
            RegisterEvidenceDocumentRequest(
                objectKey = uploadEvidence(userId, buildId, bytes),
                sceneCaptures = listOf(success("TitleScene", uploadCapture(userId, buildId, "TitleScene"))),
            ),
        )!!

        val second = service.register(
            userId,
            buildId,
            RegisterEvidenceDocumentRequest(
                objectKey = uploadEvidence(userId, buildId, bytes),
                sceneCaptures = listOf(
                    SceneCaptureRegistration(sceneName = "TitleScene", failureCode = "unsupported-render-pipeline")
                ),
            ),
        )!!

        assertThat(second.alreadyRegistered).isTrue()
        assertThat(second.documentId).isEqualTo(first.documentId)
        val rows = sceneCaptures.findByDocumentIdOrderBySceneNameAsc(second.documentId).toList()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().objectKey).isNull()
        assertThat(rows.single().failureCode).isEqualTo("unsupported-render-pipeline")
    }

    /**
     * 이미 앉아 있는 씬 행에는 등록이 값을 바로 옮긴다.
     *
     * 등록과 적재는 따로 돌아 순서가 정해져 있지 않다. 적재가 먼저 끝난 뒤 다시 등록이 오면
     * `ContentMapIngestService` 는 다시 돌지 않으므로, 이쪽이 옮기지 않으면 이미지가 영영 안 붙는다.
     */
    @Test
    fun `이미 적재된 씬 행에 이미지를 옮긴다`(): Unit = runBlocking {
        val (userId, buildId) = seed()
        val bytes = evidenceDocument()
        val first = service.register(userId, buildId, RegisterEvidenceDocumentRequest(uploadEvidence(userId, buildId, bytes)))!!
        scenes.save(SceneEntity(contentMapId = first.contentMapId, name = "TitleScene", walked = true))
        val objectKey = uploadCapture(userId, buildId, "TitleScene")

        service.register(
            userId,
            buildId,
            RegisterEvidenceDocumentRequest(
                objectKey = uploadEvidence(userId, buildId, bytes),
                sceneCaptures = listOf(success("TitleScene", objectKey)),
            ),
        )

        val scene = scenes.findByContentMapIdAndName(first.contentMapId, "TitleScene")!!
        assertThat(scene.imageObjectKey).isEqualTo(objectKey)
        assertThat(scene.imageWidth).isEqualTo(320)
        assertThat(scene.imageHeight).isEqualTo(180)
        assertThat(scene.imageCapturedAt).isNotNull()
        assertThat(scene.imageFailureCode).isNull()
    }

    /** 캡처를 모르는 옛 SDK 는 그대로 등록된다. 필드가 없다고 등록이 깨지면 붙어 있는 게임이 멎는다. */
    @Test
    fun `캡처 없는 옛 등록이 그대로 통한다`(): Unit = runBlocking {
        val (userId, buildId) = seed()

        val result = service.register(
            userId,
            buildId,
            RegisterEvidenceDocumentRequest(uploadEvidence(userId, buildId, evidenceDocument())),
        )!!

        assertThat(result.alreadyRegistered).isFalse()
        assertThat(sceneCaptures.findByDocumentIdOrderBySceneNameAsc(result.documentId).toList()).isEmpty()
    }
}
