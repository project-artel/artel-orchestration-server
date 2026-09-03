package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 판독이 보여 준 이름을 읽는 자리(ARTEL-785).
 *
 * `capability_effect.watchable` 을 근거가 추측하지 않고 관측이 채우려면, 지도가 말한 대상이
 * 판독에 나타났는지를 봐야 한다. 그 "나타났는지"가 여기서 정해진다.
 */
class PulseReadingNamesTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `statics 절을 읽는다`() {
        // 종전에는 이 절이 모델에 없어 `@JsonIgnoreProperties` 가 조용히 버렸다.
        val reading = objectMapper.readValue(
            """
            {"scene":"TurnBattleScene","reading":2510,
             "statics":[{"declaring":"InteractionLock","member":"IsLocked","value":false}]}
            """.trimIndent(),
            PulseReading::class.java,
        )

        assertThat(reading.statics).hasSize(1)
        assertThat(reading.statics.first().memberName).isEqualTo("IsLocked")
        assertThat(reading.reading).isEqualTo(2510)
    }

    /**
     * **이 구현의 가장 쉬운 실수다.**
     *
     * `observable` 범주는 `Player.HpText.text` 처럼 인스턴스 위의 값을 가리키고, 그것은 정적
     * 필드가 아니라 객체 절에 실린다. statics 만 보면 그 범주가 통째로 0 으로 보인다 — 실측에서
     * 실제로 0% 가 나왔고, 객체 절을 넣으니 18% 가 됐다.
     */
    @Test
    fun `객체 위의 멤버도 읽는다`() {
        val reading = objectMapper.readValue(
            """
            {"scene":"TurnBattleScene",
             "active":[{"path":"Canvas/Hp","members":[{"on":"UI.HpText","member":"text","value":"12"}]}]}
            """.trimIndent(),
            PulseReading::class.java,
        )

        assertThat(reading.active.first().memberNames).containsExactly("text")
    }

    @Test
    fun `컴포넌트별로 접힌 모양도 편 것과 같이 읽는다`() {
        // `by` 는 `on` 을 한 번만 쓰는 압축이다. 한쪽만 읽으면 그 문서의 이름을 통째로 놓친다.
        val reading = objectMapper.readValue(
            """
            {"active":[{"path":"Card","by":[{"on":"Cards.Card","m":[
               {"member":"cardType","value":1},{"member":"nameTMP","value":"Fire"}]}]}]}
            """.trimIndent(),
            PulseReading::class.java,
        )

        assertThat(reading.active.first().memberNames)
            .containsExactlyInAnyOrder("cardType", "nameTMP")
    }

    @Test
    fun `이름은 마지막 마디만 남는다`() {
        // 지도는 `Player.HpText.text` 같은 점 표기로, 판독은 선언 타입과 멤버로 나뉘어 온다.
        // 그 규칙으로만 맞고, `ScenarioStateReader.normalize()` 와 같은 규칙이어야 한다 —
        // 두 곳이 갈라지면 시나리오와 QA 가 다른 답을 낸다.
        val reading = objectMapper.readValue(
            """
            {"statics":[{"declaring":"A","member":"Outer.Inner.value"}],
             "active":[{"members":[{"member":"Deep.leaf"}]}]}
            """.trimIndent(),
            PulseReading::class.java,
        )

        assertThat(reading.statics.first().memberName).isEqualTo("value")
        assertThat(reading.active.first().memberNames).containsExactly("leaf")
    }

    @Test
    fun `모르는 필드가 있어도 읽는다`() {
        // 판독 문서는 SDK 가 늘려 간다. 새 절이 붙었다고 화면 적재가 멈추면 안 된다.
        val reading = objectMapper.readValue(
            """{"scene":"S","somethingNew":{"x":1},"statics":[]}""",
            PulseReading::class.java,
        )

        assertThat(reading.scene).isEqualTo("S")
    }
}
