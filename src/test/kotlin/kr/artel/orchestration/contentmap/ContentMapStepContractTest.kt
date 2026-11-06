package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.contentmap.dto.SceneStepResponse
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.entity.SpecStatus
import kr.artel.orchestration.contentmap.evidence.GroupKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 조작 단계와 조건 트리의 **wire 모양**을 못 박는다.
 *
 * home 화면(ARTEL-489)이 이 JSON 을 보고 만들어진다. 칸 이름이나 `kind` 어휘가 여기서 바뀌면
 * 상대편은 컴파일 오류 없이 조용히 못 알아듣는다 — `ScanResponseContractTest` 가 같은 이유로 있다.
 *
 * 스프링을 띄우지 않는다. 여기서 보는 것은 직렬화 결과뿐이고 DB 도 컨텍스트도 필요 없다.
 * `ObjectMapper` 는 기본 설정이면 충분하다 — 이 프로젝트는 Jackson 을 따로 설정하지 않으므로
 * 프로덕션의 것과 같은 동작이다.
 */
class ContentMapStepContractTest {

    private val objectMapper = ObjectMapper()
    private val parser = EvidenceParser(objectMapper)

    /** 단계 한 줄의 칸 아홉 개. 순서까지 못 박아, 계약에 없는 칸이 끼어드는 것도 잡는다. */
    @Test
    fun `조작 단계 한 줄의 모양`() {
        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                SceneStepResponse(
                    id = 4212,
                    summary = "`any` 키 → Scenes.GameClearController.Update()",
                    status = "runnable",
                    interaction = "press",
                    inputKey = "any",
                    controlLabel = null,
                    controlPath = null,
                    givenText = null,
                    given = ConditionNodeResponse.Always(),
                )
            )
        )

        assertThat(json.fieldNames().asSequence().toList()).containsExactly(
            "id", "summary", "status", "interaction",
            "inputKey", "controlLabel", "controlPath", "givenText", "given",
        )
        // id 는 숫자다. 문자열로 나가면 화면이 비교에서 조용히 어긋난다.
        assertThat(json["id"].isNumber).isTrue()
        assertThat(json["status"].asText()).isEqualTo("runnable")
        // 오늘은 늘 null 이다(ARTEL-447 미완). 칸이 사라지면 화면의 `givenText ?? given` 이 깨진다.
        assertThat(json["givenText"].isNull).isTrue()
    }

    /**
     * **조건을 모르는 것과 조건이 없는 것은 다른 말이다.**
     *
     * 근거 출신이 아닌 기능은 `capability_evidence` 행이 없어 조건을 아예 모른다. 그것을
     * `{kind:"always"}` 로 적으면 TC 가 "아무 때나 성립한다"는 없는 근거를 얻는다.
     */
    @Test
    fun `근거가 없으면 given 은 null 이고 always 가 아니다`() {
        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                SceneStepResponse(
                    id = 1,
                    summary = "QA 가 관측으로 배운 기능",
                    status = "needs-probe",
                    interaction = "click",
                    inputKey = null,
                    controlLabel = "확인",
                    controlPath = "Canvas/OkButton",
                    givenText = null,
                    given = null,
                )
            )
        )

        assertThat(json.has("given")).isTrue()
        assertThat(json["given"].isNull).isTrue()
    }

    /**
     * **`status` 는 kebab-case 값 그대로 나간다.** camelCase 로 바꾸지 않는다.
     *
     * `capabilities: {needsProbe, notAStep}` 이 camelCase 인 것과 헷갈리기 쉬운 자리다. 저쪽은
     * **키 이름**이고 이쪽은 **값**이라 규칙이 다르다. 값 어휘는 이 저장소에서 전부 kebab 이다 —
     * `source: static|runtime`, `capture: editor-play`, `gaps[].reason: when-missing`.
     *
     * 화면(ARTEL-497)은 kebab 만 받게 만들어져 있고 일부러 관대하지 않다. `needsProbe` 가 가면
     * "알 수 없는 상태"로 뜬다. DB 의 `status` 생성 컬럼이 내는 값을 **손대지 않고** 싣는 것이
     * 그래서 계약이다.
     */
    @Test
    fun `status 는 kebab-case 값 그대로 나간다`() {
        val wire = SpecStatus.entries
            .filter { it != SpecStatus.NOT_A_STEP } // 단계 목록에 오지 않는다
            .map { status ->
                objectMapper.readTree(
                    objectMapper.writeValueAsString(step(status = status.wire))
                )["status"].asText()
            }

        assertThat(wire).containsExactlyInAnyOrder(
            "runnable",
            "needs-probe",
            "unreachable-precondition",
        )
        // 어느 값도 camelCase 로 새지 않는다. 대문자가 하나라도 있으면 변환이 끼어든 것이다.
        assertThat(wire).allSatisfy { assertThat(it).isEqualTo(it.lowercase()) }
        assertThat(wire).noneMatch { it.contains(Regex("[A-Z]")) }
    }

    /**
     * **조건의 `kind` 도 소문자 그대로다.**
     *
     * 화면이 이 계약에 기대어 자기 내부 판별자에 대문자를 쓰고 있다. 서버가 대문자를 내보내면
     * 그쪽과 충돌한다. 내부 모델의 [GroupKind] 가 `EVERY` · `EITHER` 라 실수하기 쉬운 자리라,
     * 여섯 값을 전부 세워 둔다.
     */
    @Test
    fun `조건의 kind 는 여섯 값 전부 소문자다`() {
        val kinds = listOf(
            ConditionNode.Always,
            ConditionNode.Test("a", "==", "1", context = "this", offset = 0),
            ConditionNode.Gesture(input = "key:any (down)", offset = 0),
            ConditionNode.Group(GroupKind.EVERY, emptyList()),
            ConditionNode.Group(GroupKind.EITHER, emptyList()),
            ConditionNode.Unknown(reason = "condition", unread = null),
        ).map { objectMapper.readTree(wire(it))["kind"].asText() }

        assertThat(kinds).containsExactly(
            "always", "test", "gesture", "every", "either", "unknown",
        )
        assertThat(kinds).allSatisfy { assertThat(it).isEqualTo(it.lowercase()) }
    }

    /** 다섯 갈래가 각자의 칸을 들고 나간다. `kind` 는 늘 소문자다. */
    @Test
    fun `조건 노드 다섯 갈래의 모양`() {
        assertThat(wire(ConditionNode.Always)).isEqualTo("""{"kind":"always"}""")

        assertThat(
            wire(
                ConditionNode.Test(
                    left = "GameClearController.flag",
                    operator = "==",
                    right = "0",
                    context = "this",
                    offset = 39,
                )
            )
        ).isEqualTo(
            """{"kind":"test","left":"GameClearController.flag","operator":"==","right":"0",""" +
                """"context":"this","subjectLost":null,"offset":39}"""
        )

        assertThat(wire(ConditionNode.Gesture(input = "key:any (down)", offset = 78)))
            .isEqualTo("""{"kind":"gesture","input":"key:any (down)","offset":78}""")

        assertThat(wire(ConditionNode.Group(GroupKind.EVERY, listOf(ConditionNode.Always))))
            .isEqualTo("""{"kind":"every","parts":[{"kind":"always"}]}""")

        assertThat(wire(ConditionNode.Group(GroupKind.EITHER, listOf(ConditionNode.Always))))
            .isEqualTo("""{"kind":"either","parts":[{"kind":"always"}]}""")

        assertThat(wire(ConditionNode.Unknown(reason = "condition", unread = "operand:ldlen")))
            .isEqualTo("""{"kind":"unknown","reason":"condition","unread":"operand:ldlen"}""")
    }

    /**
     * **주어를 잃은 조건은 그 사실을 싣고 나간다.**
     *
     * `context` 가 null 인 조건은 given 으로 쓸 수 없다. 화면이 그것을 가리려면 `subjectLost` 가
     * 함께 와야 한다 — 없으면 주어 없는 비교가 멀쩡한 조건처럼 보인다.
     */
    @Test
    fun `주어를 잃은 test 는 사유를 함께 낸다`() {
        val json = objectMapper.readTree(
            wire(
                ConditionNode.Test(
                    left = "other.hp",
                    operator = "<=",
                    right = "0",
                    context = null,
                    offset = 12,
                    subjectLost = "subject-null",
                )
            )
        )

        assertThat(json["context"].isNull).isTrue()
        assertThat(json["subjectLost"].asText()).isEqualTo("subject-null")
    }

    /**
     * **평탄화 금지.** `either` 가 중첩해도 모양이 그대로 남는다.
     *
     * `either` 를 `every` 로 접으면 "둘 중 하나"가 "둘 다"가 되어, 영영 성립하지 않는 조건이 명세로
     * 나간다. 실측 문서에 `either` 를 아홉 겹 중첩한 레코드가 있다(`Cards.CardManager`).
     */
    @Test
    fun `중첩한 every 와 either 가 접히지 않는다`() {
        val tree = ConditionNode.Group(
            GroupKind.EVERY,
            listOf(
                ConditionNode.Gesture(input = "key:any (down)", offset = 78),
                ConditionNode.Group(
                    GroupKind.EITHER,
                    listOf(
                        ConditionNode.Test("a", "==", "1", context = "this", offset = 1),
                        ConditionNode.Test("b", "==", "2", context = "this", offset = 2),
                    ),
                ),
            ),
        )

        val json = objectMapper.readTree(wire(tree))

        assertThat(json["kind"].asText()).isEqualTo("every")
        assertThat(json["parts"]).hasSize(2)
        assertThat(json["parts"][1]["kind"].asText()).isEqualTo("either")
        assertThat(json["parts"][1]["parts"]).hasSize(2)
    }

    /**
     * **이름표 없는 노드는 나가지 않는다.**
     *
     * 파서는 `reason` 이 없으면 `kind` 를 사유로 쓰고, 그것마저 없으면 null 을 남긴다. 그 null 이
     * 그대로 나가면 화면이 그 줄을 그릴 수 없다 — 서버가 흡수해야 하는 지저분함이다.
     */
    @Test
    fun `사유 없는 unknown 도 이름표를 달고 나간다`() {
        val json = objectMapper.readTree(
            wire(ConditionNode.Unknown(reason = null, unread = null))
        )

        assertThat(json["kind"].asText()).isEqualTo("unknown")
        assertThat(json["reason"].asText()).isEqualTo("unknown")
        assertThat(json["unread"].isNull).isTrue()
    }

    /**
     * **정규화가 파서를 거친다.** 문서 원문이 그대로 나가지 않는다.
     *
     * 같은 트리를 서비스가 `EvidenceParser` 로 읽고 이 DTO 로 옮긴다는 것을 여기서 한 번 확인한다.
     * 별도 정규화를 쓰지 않는 것이 이 계약의 전제다 — 두 벌이면 두 곳이 서로 다르게 관대해진다.
     */
    @Test
    fun `문서 원문이 파서를 거쳐 계약 어휘로 나온다`() {
        val raw = """
            {"kind":"every","parts":[
              {"kind":"test","left":"GameClearController.flag","operator":"==","right":"0",
               "context":"this","offset":39},
              {"kind":"gesture","input":"key:any (down)","offset":78}
            ]}
        """.trimIndent()

        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                ConditionNodeResponse.of(parser.parseCondition(objectMapper.readTree(raw)))
            )
        )

        assertThat(json["kind"].asText()).isEqualTo("every")
        assertThat(json["parts"][0]["kind"].asText()).isEqualTo("test")
        assertThat(json["parts"][1]["kind"].asText()).isEqualTo("gesture")
        // 문서에 없던 칸은 null 로 채워 나간다. 화면이 칸의 유무를 분기하지 않아도 되게.
        assertThat(json["parts"][0].has("subjectLost")).isTrue()
    }

    private fun step(status: String) = SceneStepResponse(
        id = 1,
        summary = "`any` 키 → Scenes.GameClearController.Update()",
        status = status,
        interaction = "press",
        inputKey = "any",
        controlLabel = null,
        controlPath = null,
        givenText = null,
        given = ConditionNodeResponse.Always(),
    )

    private fun wire(node: ConditionNode): String =
        objectMapper.writeValueAsString(ConditionNodeResponse.of(node))
}
