package kr.artel.orchestration.contentmap.evidence

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 조건 트리의 `kind` 를 읽는 규칙.
 *
 * 이 파일이 지키는 위험은 **조용한 `Always`** 다. 파서가 노드를 못 알아보면 "조건이 없다"로 읽히고,
 * 그 순간 "아무 때나 할 수 있는 행동"과 "전제가 있는 행동"이 같은 모양이 된다. 예외도 로그도 남지
 * 않아 수를 세지 않으면 보이지 않는다 — 그래서 여기서 세고 고정한다.
 */
class ConditionKindTest {

    /**
     * 실측 문서 두 벌의 조건 어휘는 **바뀌지 않는다.**
     *
     * 두 픽스처에는 대문자 `kind` 도 이름표 없는 노드도 0건이라, 이 변경이 건드릴 것이 하나도 없다.
     * 그것이 이 단언의 내용이다 — 잘 만들어진 문서의 읽힘이 흔들리지 않았다는 증거.
     *
     * 수를 세는 방법은 [countByKind] 다. 픽스처가 다시 구워지면 그 함수로 다시 세어 손으로 맞춘다.
     * 두 문서의 분포가 서로 완전히 같은 것은 우연이 아니라 같은 게임의 연속된 캡처이기 때문이고,
     * 그래서 각각 따로 단언한다.
     */
    @Test
    fun `실측 문서 두 벌의 조건 어휘는 그대로다`() {
        val expected = mapOf(
            "test" to 855,
            "every" to 276,
            "always" to 151,
            "either" to 68,
            "gesture" to 25,
            "unknown" to 6,
        )

        assertThat(schema7.countByKind()).isEqualTo(expected)
        assertThat(schema6.countByKind()).isEqualTo(expected)
    }

    /**
     * **대문자 `EVERY` 와 이름표 없는 자식을 든 실측 트리 8개가 살아난다.**
     *
     * 고치기 전에는 이 문서 전체가 `unknown` 8개였다. 바깥 노드의 `"EVERY"` 가 `GroupKind.from` 의
     * 정확한 문자열 비교에서 떨어지면 [ConditionNode.Unknown] 이 되고, `Unknown` 은 자식을 담지
     * 않으므로 **그 아래 28개 노드가 파싱조차 되지 않는다.**
     *
     * 고친 뒤의 수가 왜 이 수인가:
     * - `every` 12 = 바깥 `"EVERY"` 8개 + 네 트리가 한 겹 더 감싼 안쪽 `"EVERY"` 4개.
     * - `gesture` 8 = 트리마다 정확히 하나. 8개가 곧 `input_key` 8개다.
     * - `test` 20 = 이름표 없는 test 모양 20개. 트리 4개는 test 2개씩(8개), 나머지 4개는 3개씩(12개).
     * - `unknown` 0 · `always` 0 — 삼켜진 것도, "항상 참"으로 둔갑한 것도 남지 않는다.
     *
     * 되살아난 노드 40개가 8개의 `unknown` 을 대신한다. `MapMove.StagePosition >= 1` 같은 진짜
     * 전제와 `key:RightArrow (down)` 같은 조작이 그 안에 들어 있다.
     */
    @Test
    fun `대문자와 이름표 없는 노드를 든 실측 트리가 살아난다`() {
        assertThat(observed.countByKind()).isEqualTo(
            mapOf("test" to 20, "every" to 12, "gesture" to 8),
        )
    }

    /**
     * 어떤 문서에서도 `unknown` 은 **늘지 않는다.**
     *
     * 위의 수들과 따로 두는 이유: 픽스처가 다시 구워지면 다음 사람이 수를 갱신하게 되는데, 그때
     * 이 변경이 막은 퇴행까지 같이 지워지지 않게 하려는 것이다. 이 단언은 수를 몰라도 성립한다.
     */
    @Test
    fun `실측 문서의 unknown 수가 기준을 넘지 않는다`() {
        assertThat(observed.countByKind()["unknown"] ?: 0).isEqualTo(0)
        assertThat(schema7.countByKind()["unknown"] ?: 0).isLessThanOrEqualTo(6)
        assertThat(schema6.countByKind()["unknown"] ?: 0).isLessThanOrEqualTo(6)
    }

    /**
     * **"조건 없음"이라고 말한 노드만 [ConditionNode.Always] 다.**
     *
     * 빈 노드가 여기 드는 이유: [ConditionNode.Always] 를 직렬화하면 정확히 `{}` 가 되고, 그 값이
     * `capability_evidence.condition_tree` 에 앉는다. 그것을 다시 [ConditionNode.Always] 로 읽어야
     * 왕복이 닫힌다.
     */
    @Test
    fun `빈 노드와 always 만 항상 참이다`() {
        assertThat(condition("{}")).isEqualTo(ConditionNode.Always)
        assertThat(condition("""{"kind":"always"}""")).isEqualTo(ConditionNode.Always)
    }

    /** 이름표가 없어도 모양이 말하는 것은 읽는다. 이 둘이 실측에서 삼켜지던 28개다. */
    @Test
    fun `이름표 없는 test 와 gesture 를 모양으로 읽는다`() {
        val test = condition(
            """{"left":"MapMove.StagePosition","right":"1","operator":">=","context":"static","offset":49}""",
        )
        assertThat(test).isEqualTo(
            ConditionNode.Test(
                left = "MapMove.StagePosition",
                operator = ">=",
                right = "1",
                context = "static",
                offset = 49,
                subjectLost = null,
            ),
        )

        assertThat(condition("""{"input":"key:RightArrow (down)","offset":21}"""))
            .isEqualTo(ConditionNode.Gesture(input = "key:RightArrow (down)", offset = 21))
    }

    /**
     * **이름표 없는 그룹은 찍지 않는다.**
     *
     * 모양은 그것이 그룹이라는 데까지만 말한다. `every` 로 찍으면 "둘 중 하나"가 "둘 다"로 뒤집혀,
     * 조건을 삼키는 것과 방향만 반대인 같은 사고가 난다. 자식을 잃는 것은 그 대가이고,
     * `unknown` 수가 늘어 눈에 보인다 — 조용한 `Always` 와 다른 점이 그것이다.
     */
    @Test
    fun `이름표 없는 그룹은 모른다고 남긴다`() {
        val nested = condition("""{"parts":[{"input":"key:RightArrow","offset":50}]}""")
        assertThat(nested).isEqualTo(
            ConditionNode.Unknown(reason = EvidenceParser.GROUP_KIND_MISSING, unread = null),
        )

        assertThat(condition("""{"parts":[]}"""))
            .isEqualTo(ConditionNode.Unknown(reason = EvidenceParser.GROUP_KIND_MISSING, unread = null))
    }

    /** 모양이 반쪽이면 읽지 않는다. `operator` 와 `right` 없이 비교를 상상하는 것이 가장 비싼 거짓 명세다. */
    @Test
    fun `반쪽 test 모양은 모른다고 남긴다`() {
        assertThat(condition("""{"left":"MapMove.position","offset":14}"""))
            .isEqualTo(ConditionNode.Unknown(reason = EvidenceParser.CONDITION_KIND_MISSING, unread = null))
    }

    /** 어휘 전체가 대소문자를 가리지 않는다. `EVERY` 만 고치면 다음 세대가 `TEST` 로 같은 사고를 낸다. */
    @Test
    fun `이름표는 대소문자를 가리지 않는다`() {
        assertThat(condition("""{"kind":"EVERY","parts":[]}"""))
            .isEqualTo(ConditionNode.Group(GroupKind.EVERY, emptyList()))
        assertThat(condition("""{"kind":"Either","parts":[]}"""))
            .isEqualTo(ConditionNode.Group(GroupKind.EITHER, emptyList()))
        assertThat(condition("""{"kind":"ALWAYS"}""")).isEqualTo(ConditionNode.Always)
        assertThat(condition("""{"kind":" Gesture ","input":"key:any","offset":3}"""))
            .isEqualTo(ConditionNode.Gesture(input = "key:any", offset = 3))
        assertThat(condition("""{"kind":"TEST","left":"a","operator":"==","right":"b","offset":1}"""))
            .isEqualTo(ConditionNode.Test(left = "a", operator = "==", right = "b", context = null, offset = 1))
    }

    /**
     * 아는 이름이 아닌 이름표는 **원문 그대로** 사유에 남는다.
     *
     * 소문자로 접어 남기면 `CapabilityKey` 에 실린 값으로 문서를 되짚을 때 찾지 못한다.
     */
    @Test
    fun `모르는 이름표는 원문을 사유로 남긴다`() {
        assertThat(condition("""{"kind":"WhenEverMaybe"}"""))
            .isEqualTo(ConditionNode.Unknown(reason = "WhenEverMaybe", unread = null))
    }

    /** 노드가 자기 사유를 들고 있으면 그것을 덮지 않는다. */
    @Test
    fun `노드가 든 사유를 덮지 않는다`() {
        assertThat(condition("""{"kind":"unknown","reason":"condition","unread":"callee"}"""))
            .isEqualTo(ConditionNode.Unknown(reason = "condition", unread = "callee"))
    }

    /**
     * 조건 자리에 객체가 아닌 것이 오면 모른다고 남긴다.
     *
     * 이 검사가 모양 추론보다 **뒤에** 있으면 문자열 조건이 "필드가 하나도 없음"에 걸려
     * [ConditionNode.Always] 가 된다. 고치기 전 코드가 정확히 그랬다.
     */
    @Test
    fun `객체가 아닌 조건은 모른다고 남긴다`() {
        val expected = ConditionNode.Unknown(reason = EvidenceParser.CONDITION_NOT_AN_OBJECT, unread = null)

        assertThat(condition(""""always"""")).isEqualTo(expected)
        assertThat(condition("[]")).isEqualTo(expected)
    }

    /**
     * **`conditionJson` 은 원문 그대로다.** 이 이슈는 읽는 쪽만 고쳤다.
     *
     * 모델은 이제 `"EVERY"` 를 [GroupKind.EVERY] 로 읽지만, 컬럼에 실릴 문자열은 여전히 문서가 쓴
     * 글자다 — 우리 모델이 못 담은 키가 조용히 사라지지 않게 하려는 결정이라 함께 고정한다.
     */
    @Test
    fun `조건 원문은 그대로 실린다`() {
        val record = observed.types.getValue("Map.MapMove").first()

        assertThat(record.condition).isInstanceOf(ConditionNode.Group::class.java)
        assertThat((record.condition as ConditionNode.Group).kind).isEqualTo(GroupKind.EVERY)
        assertThat(record.conditionJson).contains("\"EVERY\"")
    }

    private companion object {
        private val parser = EvidenceParser(ObjectMapper())

        /** 조건 노드 하나를 파서에 먹이는 가장 작은 문서. 경계 규칙을 한 줄로 물어보려고 둔다. */
        fun condition(conditionJson: String): ConditionNode =
            parser.parse(
                """
                {"schema":7,"capture":"editor","types":{"T":[
                  {"entryId":"e","methodId":"m","condition":$conditionJson}]}}
                """.trimIndent(),
            ).types.getValue("T").single().condition

        private fun fixture(name: String): EvidenceDocumentModel =
            parser.parse(File("src/test/resources/contentmap/$name").readText())

        /** 실측 schema 7 — Unity 가 로컬 스택으로 올린 문서. */
        val schema7: EvidenceDocumentModel = fixture("wv-editor-play-schema7.json")

        /** 기존 골든(schema 6). */
        val schema6: EvidenceDocumentModel = fixture("wv-editor-latest.json")

        /**
         * 대문자 `EVERY` 와 이름표 없는 자식을 든 조건 트리 8개.
         *
         * **출처를 정확히 적는다: 이 모양을 낸 것은 SDK 가 아니라 우리다.** 2026-08-26 로컬 스택의
         * `capability_evidence.condition_tree` 465행 중 여덟 행이고, 그 여덟 행이 대문자 `kind`
         * 12개와 이름표 없는 노드 28개를 **전부** 든다. 전부 `Map.MapMove::CharacterMove` 다.
         *
         * 그 여덟 행은 입력을 가르는 `either` 를 쪼갠 갈래라
         * `ContentMapIngestService.conditionJsonOf()` 가 원문 대신 타입 트리를 직렬화해 실은 것이다.
         * [GroupKind] 에 `@JsonValue` 가 없어 enum 이 이름 그대로 `"EVERY"` 로 나가고,
         * [ConditionNode.Test] 와 [ConditionNode.Gesture] 에는 `kind` 필드가 없어 이름표 없이 나간다.
         * 즉 **우리가 쓴 것을 우리가 못 읽던 것**이고, 못 읽은 자리가 "항상 참"이 됐다.
         *
         * 그 근거는 이름표 없는 test 20개가 **전부** `subjectLost` 키를 든다는 데도 있다. 근거 문서가
         * 낸 test 855개 중 그 키를 든 것은 47개뿐이다 — 데이터 클래스 필드를 빠짐없이 쓰는 우리
         * 직렬화의 지문이다. `jsonb` 를 거치며 키 순서가 다시 적혔고, 값은 그대로다.
         *
         * 문서 껍데기(`schema` · `capture` · `types` 세 키와 레코드의 식별 필드)는 이 트리들을 파서에
         * 먹이려고 손으로 지은 최소 형태다. **근거 문서가 이런 모양으로 온다는 뜻이 아니다.** 지어낸
         * `entry` · `source` · `callPath` 는 넣지 않았다.
         */
        val observed: EvidenceDocumentModel = fixture("condition-kind-observed.json")

        /**
         * 문서의 모든 레코드에서 조건 트리를 펴, 문서 어휘로 센다.
         *
         * 그룹은 [GroupKind.wire] 로 갈라 센다 — 이슈가 세는 단위가 `every` 와 `either` 이고,
         * 둘을 합치면 "둘 중 하나"가 "둘 다"로 접히는 사고가 수에 안 보인다.
         */
        fun EvidenceDocumentModel.countByKind(): Map<String, Int> =
            (types.values.flatten() + unplaced.values.flatMap { it.evidence })
                .flatMap { it.condition.flatten() }
                .groupingBy { node ->
                    when (node) {
                        is ConditionNode.Always -> "always"
                        is ConditionNode.Test -> "test"
                        is ConditionNode.Gesture -> "gesture"
                        is ConditionNode.Group -> node.kind.wire
                        is ConditionNode.Unknown -> "unknown"
                    }
                }
                .eachCount()
    }
}
