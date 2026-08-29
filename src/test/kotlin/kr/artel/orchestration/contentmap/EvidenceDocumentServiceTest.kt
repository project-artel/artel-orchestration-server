package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketRequest
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentRequest
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.ContentMapRoot
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.SceneOrigin
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
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
 * 근거 문서 수용을 검증한다.
 *
 * 이 경로에서 조용히 틀리면 **빈 지도가 쌓인다** — 문서는 받았는데 헤더를 잘못 읽어 엉뚱한 세대나
 * capture 로 기록되고, 그 위에 적재가 얹힌다. 그래서 검증 대상은 왕복이 아니라 판정이다:
 *
 * 1. 헤더를 SDK 의 신고가 아니라 **문서에서** 읽는가
 * 2. 모르는 세대를 거절하는가 — schema 5 로 읽으면 적 체력을 컨트롤 이름으로 읽는다
 * 3. 같은 문서 재전송을 건너뛰는가 — SDK 는 게임 실행마다 등록한다
 * 4. capture 가 달라도 한 빌드에 지도가 하나인가, 그리고 관측이 세운 지도에 근거가 접히는가
 * 5. 남의 빌드가 404 인가 — 부재와 권한 없음이 구분되면 id 를 훑을 수 있다
 */
@ActiveProfiles("test")
@SpringBootTest
class EvidenceDocumentServiceTest {

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
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    /**
     * 실측 문서와 같은 모양의 머리. 뒤에 큰 표가 오고 **앞부분만 읽으므로 잘린다** — 그 상태에서
     * 헤더가 나오는지가 요점이다.
     */
    private fun evidenceDocument(
        schema: Int = 6,
        capture: String = Capture.EDITOR.wire,
        digest: String = "d4b31e4da9504b7d",
        sceneCount: Int = 2_000,
    ): ByteArray {
        val scenes = (1..sceneCount).joinToString(",") { "\"Scene$it\"" }
        val bytes = """
            {
              "schema": $schema,
              "capture": "$capture",
              "capabilities": ["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"],
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

        // 이 테스트의 요점은 **잘린 JSON 에서 헤더가 나오는가**다. 문서가 읽는 양보다 작으면
        // 저장소가 통째로 돌려주어 그 경로가 아예 실행되지 않는다.
        check(bytes.size > EvidenceDocumentHeaderReader.PREFIX_BYTES) {
            "문서가 ${EvidenceDocumentHeaderReader.PREFIX_BYTES}바이트를 넘어야 잘린 경로를 검증한다 (지금 ${bytes.size})"
        }
        return bytes
    }

    private suspend fun seed(): Triple<Long, Long, Long> {
        val now = Instant.now()
        val userId = db.sql("INSERT INTO app_user (display_name) VALUES ('sdk') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
        val project = projects.save(
            ProjectEntity(name = "evidence-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
        return Triple(userId, project.id, build.id!!)
    }

    private suspend fun upload(userId: Long, buildId: Long, bytes: ByteArray): String {
        val ticket = service.createUploadTicket(userId, buildId, EvidenceUploadTicketRequest(bytes.size.toLong()))!!
        fakeStorage.put(ticket.objectKey, bytes)
        return ticket.objectKey
    }

    /**
     * 헤더를 문서에서 읽는다. SDK 는 `objectKey` 만 보내고 세대·capture·지문을 신고하지 않는다 —
     * 신고하게 하면 그 값이 문서와 어긋나도 서버가 알 수 없다.
     *
     * 문서 앞부분만 읽으므로 JSON 은 잘린 채 파서에 들어간다. 그래도 헤더가 나와야 한다.
     */
    @Test
    fun `헤더를 문서에서 직접 읽는다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val key = upload(userId, buildId, evidenceDocument())

        val result = service.register(userId, buildId, RegisterEvidenceDocumentRequest(key))!!

        assertThat(result.schemaVersion).isEqualTo(6)
        assertThat(result.capture).isEqualTo(Capture.EDITOR.wire)
        assertThat(result.evidenceDigest).isEqualTo("d4b31e4da9504b7d")
        assertThat(result.alreadyRegistered).isFalse()

        val map = contentMaps.findById(result.contentMapId)!!
        assertThat(map.unity).isEqualTo("2022.3.62f3")
        assertThat(map.backend).isEqualTo("mono")
        assertThat(map.evidencePromises.asString()).contains("selector-v1")
    }

    /**
     * 모르는 세대는 거절한다.
     *
     * 늘어나기만 하는 버전과 **뜻이 좁아진** 버전은 다르다. schema 6 에서 `label` 이 좁아졌고
     * 5 로 읽으면 적의 남은 체력을 컨트롤 이름으로 읽는다.
     */
    @Test
    fun `모르는 세대의 문서를 거절한다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val key = upload(userId, buildId, evidenceDocument(schema = 5))

        assertThatThrownBy {
            runBlocking { service.register(userId, buildId, RegisterEvidenceDocumentRequest(key)) }
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("schema 5")

        // 쓸 수 없는 지도를 남기지 않는다
        assertThat(contentMaps.findByGameBuildId(buildId)).isNull()
    }

    /** 헤더가 없는 문서도 조용히 넘어가지 않는다. */
    @Test
    fun `헤더를 못 읽으면 분명히 실패한다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val key = upload(userId, buildId, """{"objects":[]}""".toByteArray())

        assertThatThrownBy {
            runBlocking { service.register(userId, buildId, RegisterEvidenceDocumentRequest(key)) }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /**
     * SDK 는 게임 실행마다 등록하므로 같은 문서가 반복해서 온다. 같은 내용이면 새 행을 만들지 않는다.
     */
    @Test
    fun `같은 문서 재전송은 기존 등록을 돌려준다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val bytes = evidenceDocument()
        val first = service.register(userId, buildId, RegisterEvidenceDocumentRequest(upload(userId, buildId, bytes)))!!
        val second = service.register(userId, buildId, RegisterEvidenceDocumentRequest(upload(userId, buildId, bytes)))!!

        assertThat(second.alreadyRegistered).isTrue()
        assertThat(second.documentId).isEqualTo(first.documentId)
        assertThat(documents.findByContentMapIdOrderByReceivedAtDesc(first.contentMapId).toList()).hasSize(1)
    }

    /** 내용이 달라지면 새 행이다. 그 이력이 "이 빌드의 근거가 언제 달라졌나"가 된다. */
    @Test
    fun `내용이 달라지면 새 문서로 쌓인다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val first = service.register(
            userId, buildId,
            RegisterEvidenceDocumentRequest(upload(userId, buildId, evidenceDocument(digest = "aaaa"))),
        )!!
        val second = service.register(
            userId, buildId,
            RegisterEvidenceDocumentRequest(upload(userId, buildId, evidenceDocument(digest = "bbbb"))),
        )!!

        assertThat(second.alreadyRegistered).isFalse()
        assertThat(second.contentMapId).isEqualTo(first.contentMapId)
        assertThat(documents.findByContentMapIdOrderByReceivedAtDesc(first.contentMapId).toList()).hasSize(2)
        // 헤더가 갱신된다 — 지문이 바뀌었다는 것은 코드가 바뀌었다는 뜻이다
        assertThat(contentMaps.findById(first.contentMapId)!!.evidenceDigest).isEqualTo("bbbb")
    }

    /**
     * **capture 가 달라도 지도는 하나다**(ARTEL-642).
     *
     * 두 문서는 각자 남고 지도 행만 하나다 — 문서 이력이 "이 빌드의 근거가 언제 어떻게 달라졌나"를
     * 들고, 지도는 그 위에 쌓인 결과를 든다. 지도의 `capture` 는 마지막 문서가 신고한 값이고, 씬이
     * 실제로 어느 상태에서 읽혔는지는 `scene.capture` 가 답한다.
     */
    @Test
    fun `capture 가 달라도 한 빌드에 지도는 하나다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val editor = service.register(
            userId, buildId,
            RegisterEvidenceDocumentRequest(upload(userId, buildId, evidenceDocument(capture = Capture.EDITOR.wire))),
        )!!
        val player = service.register(
            userId, buildId,
            RegisterEvidenceDocumentRequest(upload(userId, buildId, evidenceDocument(capture = Capture.PLAYER.wire))),
        )!!

        assertThat(player.contentMapId).isEqualTo(editor.contentMapId)
        assertThat(documents.findByContentMapIdOrderByReceivedAtDesc(editor.contentMapId).toList()).hasSize(2)

        val map = contentMaps.findByGameBuildId(buildId)!!
        assertThat(map.id).isEqualTo(editor.contentMapId)
        assertThat(map.capture).isEqualTo(Capture.PLAYER.wire)
    }

    /**
     * **관측이 먼저 세운 지도에 근거가 들어오면 `rooted_by` 가 올라가고 씬은 살아남는다.**
     *
     * 순서가 결과를 바꾸지 않는 것이 이 story 의 요점이다. QA 런이 먼저 돌아 지도와 씬을 세운
     * 빌드에서 나중에 근거 문서가 오면, 새 지도를 만드는 대신 그 행에 접힌다. 새로 만들면 관측이
     * 벌어 온 씬과 그 아래 화면·관측이 통째로 다른 지도에 갇힌다.
     *
     * 씬을 지우지 않는 것도 함께 지킨다 — 등록은 헤더만 갱신하고 씬은 적재가 소유한다.
     */
    @Test
    fun `관측이 세운 지도에 근거가 들어오면 rooted_by 가 올라간다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val observed = contentMaps.save(
            ContentMapEntity(gameBuildId = buildId, rootedBy = ContentMapRoot.OBSERVATION.wire)
        )
        val scene = scenes.save(
            SceneEntity(
                contentMapId = observed.id!!,
                name = "ObservedOnlyScene",
                origin = SceneOrigin.OBSERVED.wire,
            )
        )

        val registered = service.register(
            userId, buildId,
            RegisterEvidenceDocumentRequest(upload(userId, buildId, evidenceDocument())),
        )!!

        assertThat(registered.contentMapId).isEqualTo(observed.id)
        val map = contentMaps.findByGameBuildId(buildId)!!
        assertThat(map.rootedBy).isEqualTo(ContentMapRoot.EVIDENCE.wire)
        assertThat(map.capture).isEqualTo(Capture.EDITOR.wire)
        assertThat(map.evidenceDigest).isEqualTo("d4b31e4da9504b7d")
        assertThat(scenes.findById(scene.id!!)?.origin).isEqualTo(SceneOrigin.OBSERVED.wire)
    }

    /**
     * 남의 빌드는 없는 것과 같다.
     *
     * 부재와 권한 없음을 구분해 알려주면 id 를 훑어 남의 빌드가 존재한다는 사실을 알아낼 수 있다.
     */
    @Test
    fun `남의 빌드는 보이지 않는다`(): Unit = runBlocking {
        val (_, _, buildId) = seed()
        val stranger = db.sql("INSERT INTO app_user (display_name) VALUES ('stranger') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

        assertThat(service.createUploadTicket(stranger, buildId, EvidenceUploadTicketRequest(10))).isNull()
        assertThat(service.register(stranger, buildId, RegisterEvidenceDocumentRequest("x"))).isNull()
    }

    /** 다른 빌드에 올린 키를 등록해 그 문서를 가져오지 못한다. */
    @Test
    fun `다른 빌드의 키는 등록되지 않는다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val (otherUser, _, otherBuild) = seed()
        val otherKey = upload(otherUser, otherBuild, evidenceDocument())

        assertThatThrownBy {
            runBlocking { service.register(userId, buildId, RegisterEvidenceDocumentRequest(otherKey)) }
        }.isInstanceOf(BadRequestException::class.java)
    }

    /**
     * 헤더 앞에 모르는 필드가 끼어도 읽는다.
     *
     * 근거 문서의 계약은 **더하기만 한다**(`capabilities` 가 그렇게 못 박혀 있다). 모르는 필드에서
     * 읽기를 끊으면 SDK 가 세대를 올리지 않고 필드 하나만 더한 날 모든 문서가 거절된다.
     */
    @Test
    fun `헤더 앞에 모르는 필드가 있어도 읽는다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val scenes = (1..2_000).joinToString(",") { "\"Scene$it\"" }
        val bytes = """
            {
              "generatedAt": "2026-08-18T00:00:00Z",
              "schema": 6,
              "note": {"nested": ["also", "unknown"]},
              "capture": "editor",
              "capabilities": ["build-info-v1"],
              "build": {"evidence": "d4b31e4da9504b7d", "unity": "2022.3.62f3"},
              "scenes": [$scenes]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val result = service.register(userId, buildId, RegisterEvidenceDocumentRequest(upload(userId, buildId, bytes)))!!

        assertThat(result.schemaVersion).isEqualTo(6)
        assertThat(result.evidenceDigest).isEqualTo("d4b31e4da9504b7d")
    }

    /**
     * `capabilities` 를 못 읽으면 조용히 빈 약속으로 넘어가지 않는다.
     *
     * 빈 약속은 적재기가 `control_selector` 를 채우지 않아야 한다는 뜻인데, "약속이 없다"와
     * "우리가 못 읽었다"가 같은 값이 되면 그 구분이 사라진다.
     */
    @Test
    fun `약속 목록이 없으면 거절한다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val bytes = """
            {"schema": 6, "capture": "editor", "build": {"evidence": "aaaa"}, "objects": []}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        assertThatThrownBy {
            runBlocking { service.register(userId, buildId, RegisterEvidenceDocumentRequest(upload(userId, buildId, bytes))) }
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("capabilities")
    }

    /** 모르는 capture 는 거절한다. */
    @Test
    fun `모르는 capture 를 거절한다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val key = upload(userId, buildId, evidenceDocument(capture = "headless"))

        assertThatThrownBy {
            runBlocking { service.register(userId, buildId, RegisterEvidenceDocumentRequest(key)) }
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("headless")
    }

    /**
     * 컬럼 폭을 넘는 헤더 문자열은 400 이다.
     *
     * 통과시키면 INSERT 가 터져 500 이 나가고, 신뢰할 수 없는 문서로 서버 오류를 만들 수 있게 된다.
     */
    @Test
    fun `너무 긴 지문은 400 이다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val key = upload(userId, buildId, evidenceDocument(digest = "f".repeat(64)))

        assertThatThrownBy {
            runBlocking { service.register(userId, buildId, RegisterEvidenceDocumentRequest(key)) }
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("build.evidence")
    }

    /**
     * 옛 문서를 다시 보내도 지도의 지문이 되돌아가지 않는다.
     *
     * 같은 version 으로 옛 빌드를 다시 돌리면 실제로 일어난다. 되돌아가면 "지문이 다르면 코드가
     * 바뀐 것"이 거짓으로 발화해 evidence 출신 기능의 검증이 통째로 되돌려진다.
     */
    @Test
    fun `옛 문서 재전송이 지문을 되돌리지 않는다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val old = evidenceDocument(digest = "aaaa")
        val first = service.register(userId, buildId, RegisterEvidenceDocumentRequest(upload(userId, buildId, old)))!!
        service.register(
            userId, buildId,
            RegisterEvidenceDocumentRequest(upload(userId, buildId, evidenceDocument(digest = "bbbb"))),
        )

        val again = service.register(userId, buildId, RegisterEvidenceDocumentRequest(upload(userId, buildId, old)))!!

        assertThat(again.alreadyRegistered).isTrue()
        assertThat(contentMaps.findById(first.contentMapId)!!.evidenceDigest).isEqualTo("bbbb")
    }

    /**
     * 이미 아는 내용이 다시 오면 방금 올라온 객체를 지운다.
     *
     * SDK 는 실행마다 새 키에 올리므로, 지우지 않으면 **가장 흔한 경로**에서 1.4MB 고아가 매번
     * 쌓인다. 예외 상황이 아니라 정상 상태다.
     */
    @Test
    fun `재전송으로 올라온 객체는 남기지 않는다`(): Unit = runBlocking {
        val (userId, _, buildId) = seed()
        val bytes = evidenceDocument()
        val firstKey = upload(userId, buildId, bytes)
        service.register(userId, buildId, RegisterEvidenceDocumentRequest(firstKey))

        val secondKey = upload(userId, buildId, bytes)
        service.register(userId, buildId, RegisterEvidenceDocumentRequest(secondKey))

        assertThat(fakeStorage.bytesOf(firstKey)).isNotNull()
        assertThat(fakeStorage.bytesOf(secondKey)).isNull()
    }
}
