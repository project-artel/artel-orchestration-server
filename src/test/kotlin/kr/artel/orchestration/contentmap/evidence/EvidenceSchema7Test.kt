package kr.artel.orchestration.contentmap.evidence

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.service.EvidenceDocumentHeaderReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/**
 * schema 7 을 읽는다. **두 세대를 한 파서가 받는다는 것이 요점이다.**
 *
 * 7 이 바꾼 것은 `createdBy` 항목의 모양뿐이다 — 문자열 `"<OwnerType>.<field>"` 가
 * `{field, prefab, prefabId}` 객체가 됐다. 늘어나기만 한 변경이라 6 의 뜻은 좁아지지 않았고,
 * 그래서 게이트가 둘 다 받는다.
 *
 * 이 파일이 지키는 진짜 위험은 **조용한 0건**이다. 객체를 문자열로 읽으면 Jackson 이 빈 문자열을
 * 돌려주고, 스폰 귀속이 자리를 하나도 못 찾아 프리팹 위에만 사는 타입이 통째로 사라진다.
 * 예외도 로그도 없이 전투 씬이 비는 종류의 실패다.
 *
 * 픽스처는 실측이다 — 2026-08-26 로컬 스택에서 Unity 가 실제로 올려 S3 에 앉은 문서를 그대로 가져왔다.
 */
class EvidenceSchema7Test {

    /**
     * 게이트가 6 과 7 만 받는다.
     *
     * 상한을 없애고 `>= 6` 으로 두면, 뜻이 좁아진 세대가 왔을 때 조용히 잘못 읽는다. schema 6 에서
     * `label` 이 "오브젝트가 보여주는 것"에서 "누를 수 있는 것에 쓰인 글자"로 좁아진 전례가 있다.
     */
    @Test
    fun `게이트가 6 과 7 을 받는다`() {
        assertThat(EvidenceDocumentHeaderReader.SUPPORTED_SCHEMA_VERSIONS).containsExactlyInAnyOrder(6, 7)
    }

    /** 실측 schema 7 문서가 파싱된다. 이 문서는 `editor-play` 캡처다. */
    @Test
    fun `schema 7 문서를 읽는다`() {
        assertThat(schema7.schema).isEqualTo(7)
        assertThat(schema7.capture).isEqualTo("editor-play")
        assertThat(schema7.scenes).containsExactly(
            "TitleScene", "StoryScene", "Map_scene", "GameClearScene",
            "GameOverScene", "TurnBattleScene", "EndingScene",
        )
    }

    /**
     * **`createdBy` 가 비지 않는다.** 이 단언 하나가 조용한 0건을 막는다.
     *
     * 실측: unplaced 7개 중 3개가 `createdBy` 를 들고, 항목은 모두 18개다. 객체를 문자열로 읽으면
     * 이 수가 그대로 0이 된다 — 파싱은 성공하고 행만 사라진다.
     */
    @Test
    fun `createdBy 가 객체로 와도 항목이 살아 있다`() {
        val filled = schema7.unplaced.filterValues { it.createdBy.isNotEmpty() }

        assertThat(filled).hasSize(3)
        assertThat(filled.values.sumOf { it.createdBy.size }).isEqualTo(18)
        assertThat(filled.values.flatMap { it.createdBy }).allSatisfy {
            assertThat(it.field).isNotBlank()
        }
    }

    /**
     * **프리팹이 필드보다 많다. 그것이 schema 7 이 생긴 이유다.**
     *
     * 실측에서 필드는 셋뿐인데 프리팹은 열이다. `BossEnemy.fireShoot` 와 `MagicEnemy.fireShoot` 가
     * 서로 다른 프리팹을 만드는데, schema 6 의 문자열만으로는 그 둘이 같은지 다른지 답이 없었다.
     */
    @Test
    fun `한 필드가 여러 프리팹을 만든다`() {
        val entries = schema7.unplaced.values.flatMap { it.createdBy }

        assertThat(entries.map { it.field }.distinct()).containsExactlyInAnyOrder(
            "Combat.Enemies.BossEnemy.fireShoot",
            "Combat.Enemies.EnemyPoolController.enemyDataContainer",
            "Combat.Enemies.MagicEnemy.fireShoot",
        )
        assertThat(entries.mapNotNull { it.prefab }.distinct()).hasSize(10)
        // 문서 안에서만 뜻이 있는 번호다. 행에 저장해 다음 스캔과 비교하면 안 된다.
        assertThat(entries.mapNotNull { it.prefabId }.distinct()).hasSize(10)

        val boss = entries.filter { it.field.endsWith("BossEnemy.fireShoot") }.mapNotNull { it.prefab }
        val magic = entries.filter { it.field.endsWith("MagicEnemy.fireShoot") }.mapNotNull { it.prefab }
        assertThat(boss).isNotEmpty()
        assertThat(magic).isNotEmpty()
        assertThat(boss).doesNotContainAnyElementsOf(magic)
    }

    /**
     * schema 6 의 문자열 형식도 그대로 읽힌다.
     *
     * 이미 앉은 지도와 저장소의 골든 픽스처가 6 이다. 7 을 받으면서 6 을 놓치면 재적재가 전부 깨진다.
     */
    @Test
    fun `schema 6 문자열 형식도 계속 읽힌다`() {
        val filled = schema6.unplaced.filterValues { it.createdBy.isNotEmpty() }

        assertThat(schema6.schema).isEqualTo(6)
        // 실측 골든에서 unplaced 14개 중 10개가 createdBy 를 들고, 그것이 근거 111건을 든다.
        assertThat(filled).hasSize(10)
        assertThat(filled.values.flatMap { it.createdBy }).allSatisfy {
            assertThat(it.field).isNotBlank()
            // 6 은 프리팹을 말하지 않는다. 없는 것을 지어내지 않는지 함께 본다.
            assertThat(it.prefab).isNull()
            assertThat(it.prefabId).isNull()
        }
    }

    /** 모르는 세대는 그대로 거절하고, 몇 번이 왔는지 말한다. */
    @Test
    fun `모르는 세대는 거절한다`() {
        val prefix = """{"schema":99,"capture":"editor","build":{},"capabilities":[]}""".toByteArray()

        assertThatThrownBy { EvidenceDocumentHeaderReader.read(prefix, ObjectMapper()) }
            .hasMessageContaining("schema 99")
    }

    private companion object {
        private val parser = EvidenceParser(ObjectMapper())

        /** 실측 schema 7 — Unity 가 로컬 스택으로 실제 업로드한 문서다. */
        val schema7: EvidenceDocumentModel =
            parser.parse(File("src/test/resources/contentmap/wv-editor-play-schema7.json").readText())

        /** 기존 골든. 6 이 계속 읽히는지 보는 대조군이다. */
        val schema6: EvidenceDocumentModel =
            parser.parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())
    }
}
