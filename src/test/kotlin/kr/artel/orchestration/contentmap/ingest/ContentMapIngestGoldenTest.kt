package kr.artel.orchestration.contentmap.ingest

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 실측 근거 문서 한 장을 통째로 적재하고 **표에 앉은 결과를 수치로 박제한다.**
 *
 * 단계별 테스트(파서 · 조인 · 스키마)는 각자 자기 규칙만 본다. 여기서 보는 것은 그 규칙들이
 * 이어졌을 때 **DB 가 실제로 받아 주는가**다. 조인이 아무리 옳은 후보를 내도 CHECK 하나에 걸리면
 * 문서 하나가 통째로 되돌아가고, 그 실패는 단위 테스트 어디에서도 보이지 않는다.
 *
 * 수치를 박아 두는 이유: 문서가 바뀌거나 규칙이 바뀌면 여기가 깨져야 다시 보게 된다. 숫자만 고치는
 * 사람이 없도록 각 테스트의 KDoc 에 **그 수가 어디서 오는지**를 적는다. 실측(2026-08-19,
 * `wv-editor-latest.json`, schema 6 · editor):
 *
 * | 값 | 수 | 어디서 |
 * |---|---|---|
 * | 문서의 씬 | 7 | `scenes` 배열. 후보의 씬도 이 7 안에 든다 |
 * | 조인 후보 | 529 | `EvidenceJoin.candidates()` |
 * | 기능 행 | 491 | 후보 중 38건은 같은 (given, when) 의 다른 조각이라 한 줄로 접힌다 |
 * | 스폰 출신 행 | 98 | 스폰 후보 111건이 접힌 뒤의 수 |
 * | 조작을 든 것 | 51 | click 24 + press 27. 나머지 478 은 `interaction='none'` |
 * | 효과 | 486 | 후보들이 든 `effects[]` 의 합 |
 *
 * **전제 하나가 이 파일 전체를 떠받친다 — `capability_key` 가 같은 후보는 같은 명세여야 한다.**
 * 적재기는 키로 후보를 묶어 한 줄로 쓰고(효과는 합친다), 몇 건을 접었는지 `IngestResult.collapsed`
 * 에 남긴다. 키가 서로 다른 스텝을 접기 시작하면 그 수가 뛰고, TC 가 시험하지 않는 기능이 생긴다 —
 * 그때 이 테스트는 **적재 결과가 아니라 키 산식**을 가리키고 있는 것이다
 * (`CapabilityKeyTest` 가 접히는 것이 정말 같은 (given, when) 인지 따로 확인한다).
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentMapIngestGoldenTest {

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
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var db: DatabaseClient

    private lateinit var result: IngestResult
    private var contentMapId: Long = 0
    private var documentId: Long = 0

    /**
     * 1.4MB 를 테스트마다 다시 적재하지 않는다. 적재는 문서 하나가 한 트랜잭션이라, 한 번 돌려 놓고
     * 그 결과를 여러 각도에서 읽는 것이 이 파일이 하는 일과도 맞는다.
     */
    @BeforeAll
    fun `골든 문서를 한 번 적재한다`(): Unit = runBlocking {
        val bytes = File(GOLDEN_PATH).readBytes()
        val map = newContentMap()
        contentMapId = map.id!!

        val objectKey = "content-map/${map.id}/wv-editor-latest.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)

        val document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId,
                objectKey = objectKey,
                contentHash = sha256Hex(bytes),
                byteSize = bytes.size.toLong(),
            )
        )
        documentId = document.id!!

        result = ingest.ingest(document)
    }

    /**
     * 문서 한 장이 씬 7 · 기능 491 로 앉는다.
     *
     * 후보는 529건인데 행은 491건이다. 나머지 38건은 씬·진입점·메서드·갈래·조건·조작이 전부 같고
     * `effects` 나 `recordKind` 만 다른 조각이라, 같은 명세 한 줄에 효과가 함께 달린다. 따로 쓰면
     * TC 가 같은 조작을 두 번 시험한다.
     *
     * 이 수가 줄면 **근거가 조용히 사라진 것이다.** 조인이 후보를 냈는데 적재가 옮기지 못하면 표는
     * 그냥 작아질 뿐이고, 없는 기능은 아무 데서도 오류를 내지 않는다. `capability` 는 후보와 1:1 이라
     * [IngestResult.capabilities] 와 표의 행 수가 같아야 한다.
     *
     * 씬을 따로 세는 이유: 후보의 씬은 문서의 `scenes` 목록에 없을 수도 있고, 그때 씬을 만들지 않으면
     * FK 가 적재를 통째로 거절한다. 실측에서는 둘이 같은 7 이라, 이 값이 7 을 넘으면 조인이 문서에
     * 없는 씬 이름을 만들기 시작한 것이다.
     */
    @Test
    fun `골든 문서가 씬 7개와 기능 491개로 앉는다`(): Unit = runBlocking {
        assertThat(result.documentId).isEqualTo(documentId)
        assertThat(result.contentMapId).isEqualTo(contentMapId)
        assertThat(result.scenes).isEqualTo(7)
        assertThat(result.capabilities).isEqualTo(491)
        // 접힌 수도 함께 못 박는다. 갑자기 뛰면 키 산식이 무언가를 잃기 시작한 것이다.
        assertThat(result.collapsed).isEqualTo(38)
        // 첫 적재라 내릴 옛 기능이 없다. 여기가 0 이 아니면 남의 지도를 건드린 것이다.
        assertThat(result.deleted).isZero()
        assertThat(result.markedNotApplicable).isZero()

        val stored = scenes.findByContentMapIdOrderByNameAsc(contentMapId).toList()
        assertThat(stored.map { it.name }).containsExactlyInAnyOrder(
            "TitleScene",
            "StoryScene",
            "Map_scene",
            "GameClearScene",
            "GameOverScene",
            "TurnBattleScene",
            "EndingScene",
        )
        assertThat(countOfMap("SELECT count(*) FROM capability WHERE content_map_id = :id")).isEqualTo(491)
    }

    /**
     * 도장이 찍힌다. **도장이 있으면 행이 다 있다는 뜻**이어야 한다.
     *
     * 적재와 같은 트랜잭션에서 찍기 때문에 성립하는 말이다. 따로 찍으면 절반만 적재된 문서가 "처리됨"
     * 으로 보이고, 그 뒤 아무도 다시 적재하지 않아 빈 지도가 영영 남는다.
     *
     * 버전을 함께 보는 이유: 적재 규칙을 고쳤을 때 낡은 판으로 처리된 문서부터 다시 돌려야 하는데,
     * 그 선별의 유일한 근거가 이 칸이다.
     */
    @Test
    fun `적재한 문서에 적재기 판과 시각이 찍힌다`(): Unit = runBlocking {
        val stamped = documents.findById(documentId)!!

        assertThat(stamped.ingestedBy).isEqualTo(ContentMapIngestService.INGESTER_VERSION)
        assertThat(stamped.ingestedAt).isNotNull()
        assertThat(stamped.ingestedAt).isBeforeOrEqualTo(Instant.now())

        // 도장이 찍힌 문서는 대기열에서 빠진다. 안 빠지면 워커가 같은 1.4MB 를 영원히 다시 읽는다.
        assertThat(documents.findPending(50).toList().map { it.id }).doesNotContain(documentId)
    }

    /**
     * 스폰 출신 98행이 V46 의 모양 그대로 앉고, **TC 창구에는 나타나지 않는다.**
     *
     * 프리팹에는 씬 경로가 없다. 그것을 쥔 오브젝트의 경로를 조준 대상으로 내주면 TC 는 카드 매니저를
     * 눌러 카드가 뒤집혔다고 적는다 — 조작이 아닌데 조작인 척하는 이 한 줄이 근거 없는 명세 중 가장
     * 비싸다. `ck_capability_spawn_has_no_control` 이 그것을 INSERT 단계에서 막지만, CHECK 는 우리가
     * 그 값을 **보내지 않을 때만** 조용하다. 적재기가 후보의 칸을 그대로 믿으면 문서 하나가 통째로
     * 되돌아간다.
     *
     * 98 은 `unplaced` 10타입의 evidence 111건이 같은 명세끼리 접힌 뒤의 수다. 0 이 되면 `createdBy`
     * 추적이 끊긴 것이고,
     * 그때 전투 씬의 기능이 거의 다 사라진다.
     */
    @Test
    fun `스폰 출신 행은 조준 대상을 갖지 않고 TC 창구에서 빠진다`(): Unit = runBlocking {
        assertThat(
            countOfMap("SELECT count(*) FROM capability WHERE content_map_id = :id AND spawned_by_field IS NOT NULL")
        ).isEqualTo(98)

        // V46 이 요구하는 모양을 어긴 행이 하나도 없다.
        assertThat(
            countOfMap(
                """
                SELECT count(*) FROM capability
                WHERE content_map_id = :id
                  AND spawned_by_field IS NOT NULL
                  AND NOT (
                      control_path IS NULL
                      AND control_selector IS NULL
                      AND control_label IS NULL
                      AND interaction = 'none'
                      AND input_key IS NULL
                      AND input_phase IS NULL
                      AND actionability = 'not-a-step'
                  )
                """
            )
        ).isZero()

        // 스폰 행은 status 가 'not-a-step' 으로 유도돼 뷰가 거른다. 새면 실행기가 누를 자리 없이
        // 누르라는 스텝을 받는다.
        assertThat(
            countOfMap(
                """
                SELECT count(*) FROM v_content_map_capability v
                JOIN capability c ON c.id = v.capability_id
                WHERE v.content_map_id = :id AND c.spawned_by_field IS NOT NULL
                """
            )
        ).isZero()

        // 창구에 남는 것은 조작을 든 51 뿐이다(click 24 + press 27). 529 - 478.
        assertThat(countOfMap("SELECT count(*) FROM v_content_map_capability WHERE content_map_id = :id"))
            .isEqualTo(51)
    }

    /**
     * 배선으로 걸린 컨트롤이 창구에 살아 있고, **실행 축과 관측 축이 각자 답한다.**
     *
     * `Canvas/continue` 를 짚는 이유: 이 자리가 arrivals 경로로만 컨트롤에 닿는 유일한 자리다
     * (`Core.SaveLoadController`). 그 길이 끊기면 여기가 먼저 빈다.
     *
     * 다섯 행 중 `runnable` 은 둘뿐이다. 나머지 셋은 `needs-probe` 다 — 실행 축은 셋 다 `runnable`
     * 이지만(클릭할 자리가 있다), 근거가 `observable`·`availability` 효과를 하나도 말하지 않으면
     * 관측 축이 `unknown` 으로 남고 V45 의 유도 규칙이 status 를 `needs-probe` 로 내린다. 즉
     * **"누를 수는 있는데 무엇이 달라지는지 근거가 말하지 않는다"** 가 이 셋의 정직한 상태다.
     * ARTEL-452 가 판독과 대조해 이 축을 덮어쓸 때 이 수가 움직인다.
     */
    @Test
    fun `배선으로 걸린 컨트롤이 창구에 실행 축과 함께 나온다`(): Unit = runBlocking {
        val rows = db
            .sql(
                """
                SELECT scene_name, status, actionability, observability, control_selector
                FROM v_content_map_capability
                WHERE content_map_id = :id AND control_path = 'Canvas/continue'
                ORDER BY capability_id
                """
            )
            .bind("id", contentMapId)
            .map { row, _ ->
                listOf(
                    row.get("scene_name", String::class.java),
                    row.get("status", String::class.java),
                    row.get("actionability", String::class.java),
                    row.get("observability", String::class.java),
                    row.get("control_selector", String::class.java),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(rows).hasSize(5)
        assertThat(rows.map { it[0] }).containsOnly("TitleScene")
        // 실행 축은 다섯 다 runnable 이다. 갈리는 것은 관측 축뿐이다.
        assertThat(rows.map { it[2] }).containsOnly("runnable")
        assertThat(rows.map { it[3] }).containsExactlyInAnyOrder(
            "observable", "observable", "unknown", "unknown", "unknown",
        )
        assertThat(rows.map { it[1] }).containsExactlyInAnyOrder(
            "runnable", "runnable", "needs-probe", "needs-probe", "needs-probe",
        )
        // 문서가 `selector-v1` 을 약속했으므로 조준용 경로가 함께 실린다. 없으면 실행기가 형제 중
        // 어느 것을 누를지 못 고른다.
        assertThat(rows.map { it[4] }).containsOnly("Canvas[2]/continue[2]")
    }

    /**
     * 근거의 조건 트리가 **원문 그대로** 실리고, 효과가 IL 위치를 들고 온다.
     *
     * 조건을 우리 타입 트리에서 되쓰면 우리가 못 담은 키가 조용히 사라진다. 사라진 뒤에는 "근거에
     * 없었다"와 "우리가 버렸다"가 구분되지 않는다. 그래서 `condition_tree` 는 문서가 준 JSON 과
     * jsonb 로 같아야 한다.
     *
     * 짚는 자리는 `Scenes.TitleSceneManager::LoadStoryScene` 의 `LoadPlayData() != -1` 갈래다. 같은
     * 메서드가 조건만 달리해 세 번 나오므로, 조건이 뭉개지면 셋이 한 줄로 접히는 것이 여기서 먼저
     * 보인다.
     *
     * `il_offset` 은 그 갈래가 씬을 바꾸는 지점(`@47`)이다. 이 값이 없으면 효과가 어느 지점의 것인지
     * 알 수 없어, 같은 메서드의 서로 다른 결과가 구분되지 않는다.
     */
    @Test
    fun `근거의 조건과 효과의 IL 위치가 원문대로 실린다`(): Unit = runBlocking {
        val condition = db
            .sql(
                """
                SELECT ce.condition_tree ->> 'left'     AS left_side,
                       ce.condition_tree ->> 'operator' AS operator,
                       ce.condition_tree ->> 'offset'   AS branch_offset,
                       ce.branch_offset::text           AS row_branch_offset,
                       (ce.condition_tree = CAST(:condition AS jsonb))::text AS same_as_document
                FROM capability c
                JOIN capability_evidence ce ON ce.capability_id = c.id
                WHERE c.content_map_id = :id
                  AND c.control_path = 'Canvas/continue'
                  AND ce.method_id = :methodId
                  AND ce.condition_tree ->> 'operator' = '!='
                """
            )
            .bind("id", contentMapId)
            .bind("methodId", LOAD_STORY_SCENE_METHOD_ID)
            .bind("condition", LOAD_STORY_SCENE_CONDITION)
            .map { row, _ ->
                listOf(
                    row.get("left_side", String::class.java),
                    row.get("operator", String::class.java),
                    row.get("branch_offset", String::class.java),
                    row.get("row_branch_offset", String::class.java),
                    row.get("same_as_document", String::class.java),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(condition).hasSize(1)
        assertThat(condition[0][0]).isEqualTo("TitleSceneManager.saveLoadController.LoadPlayData()")
        assertThat(condition[0][2]).isEqualTo("12")
        // 갈래를 가른 IL 위치는 컬럼으로도 올라온다. 조건 트리 안의 offset 과 같은 값이다.
        assertThat(condition[0][3]).isEqualTo("12")
        // 원문과 jsonb 로 같다. 키 하나라도 빠지면 여기가 false 다.
        assertThat(condition[0][4]).isEqualTo("true")

        val effects = db
            .sql(
                """
                SELECT e.category, e.kind, e.target, e.il_offset::text AS il_offset
                FROM capability c
                JOIN capability_evidence ce ON ce.capability_id = c.id
                JOIN capability_effect e ON e.capability_id = c.id
                WHERE c.content_map_id = :id
                  AND c.control_path = 'Canvas/continue'
                  AND ce.method_id = :methodId
                  AND ce.condition_tree ->> 'operator' = '!='
                ORDER BY e.il_offset
                """
            )
            .bind("id", contentMapId)
            .bind("methodId", LOAD_STORY_SCENE_METHOD_ID)
            .map { row, _ ->
                listOf(
                    row.get("category", String::class.java),
                    row.get("kind", String::class.java),
                    row.get("target", String::class.java),
                    row.get("il_offset", String::class.java),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(effects).containsExactly(listOf("observable", "scene", "Map_scene", "47"))

        // 문서의 모든 효과가 위치를 들고 온다. 근거의 `effects[].offset` 은 항상 있는 값이라,
        // 여기서 null 이 나오면 옮기는 길에서 잃은 것이다.
        assertThat(
            countOfMap(
                """
                SELECT count(*) FROM capability_effect e
                JOIN capability c ON c.id = e.capability_id
                WHERE c.content_map_id = :id AND e.origin = 'evidence' AND e.il_offset IS NULL
                """
            )
        ).isZero()
        assertThat(
            countOfMap(
                """
                SELECT count(*) FROM capability_effect e
                JOIN capability c ON c.id = e.capability_id
                WHERE c.content_map_id = :id
                """
            )
        ).isEqualTo(486)
    }

    /**
     * 모든 기능이 64자 안정 키를 들고, 그 키가 서로 다르다.
     *
     * 키는 `capability.id` 가 못 하는 일을 한다 — `scene_edge.capability_id` 와
     * `screen_transition.capability_id` 는 런타임에 알아낸 지식을 든 참조인데, 재적재가 evidence 행을
     * 지웠다 넣으면 BIGSERIAL 번호가 통째로 밀린다. 밀린 뒤에는 그 지식이 엉뚱한 기능에 붙는다.
     *
     * **겹치지 않는다는 것이 이 표의 전제다.** 겹치면 `uk_capability_map_key` 가 적재를 거절하거나, 더
     * 나쁘게는 서로 다른 스텝 둘이 한 줄로 접혀 TC 가 그중 하나를 영영 시험하지 않는다. 실측 문서에서
     * 기능 491행이 모두 다른 키를 가져야 하고, 그 판정의 입력은 씬 · owner · entry · 메서드 · 갈래 위치 ·
     * 조건 · 키 입력 · 컨트롤 경로 · 스폰 필드 여덟 칸이다.
     */
    @Test
    fun `기능마다 64자 안정 키가 있고 서로 겹치지 않는다`(): Unit = runBlocking {
        assertThat(
            countOfMap(
                """
                SELECT count(*) FROM capability
                WHERE content_map_id = :id
                  AND (capability_key IS NULL OR length(capability_key) <> 64)
                """
            )
        ).isZero()

        assertThat(countOfMap("SELECT count(DISTINCT capability_key) FROM capability WHERE content_map_id = :id"))
            .isEqualTo(491)
    }

    private suspend fun countOfMap(sql: String): Int = db
        .sql(sql)
        .bind("id", contentMapId)
        .map { row, _ -> (row.get(0) as Number).toInt() }
        .one()
        .awaitSingle()

    /** 게임 빌드는 프로젝트에 FK 로 매달려 있어 프로젝트부터 만든다. */
    private suspend fun newContentMap(): ContentMapEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(
                name = "ingest-golden-${System.nanoTime()}",
                genre = "ACTION",
                createdAt = now,
                updatedAt = now,
            )
        )
        val build = gameBuilds.save(
            GameBuildEntity(
                projectId = project.id!!,
                version = "v${System.nanoTime()}",
                createdAt = now,
                updatedAt = now,
            )
        )
        return contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3",
                backend = "mono",
                development = true,
                sdkVersion = "0.1.0",
            )
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val GOLDEN_PATH = "src/test/resources/contentmap/wv-editor-latest.json"

        /**
         * 문서가 그대로 든 조건 원문. 손으로 옮긴 것이 아니라 픽스처의
         * `types["Scenes.TitleSceneManager"]` 에서 그대로 가져온 값이다.
         */
        /** 조건 셋이 붙어 있는 메서드. `method` 칸이 아니라 안정 주소인 `method_id` 로 짚는다. */
        const val LOAD_STORY_SCENE_METHOD_ID =
            "Assembly-CSharp|Scenes.TitleSceneManager|LoadStoryScene|System.Void()"

        const val LOAD_STORY_SCENE_CONDITION =
            """{"kind":"test","left":"TitleSceneManager.saveLoadController.LoadPlayData()","operator":"!=","right":"-1","context":"this","offset":12}"""
    }
}
