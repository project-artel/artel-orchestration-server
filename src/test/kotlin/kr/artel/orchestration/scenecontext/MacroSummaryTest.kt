package kr.artel.orchestration.scenecontext

import kr.artel.orchestration.scenecontext.service.firstBodyComment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 요약 한 줄을 `macro` 원문에서 뽑는 규칙(ARTEL-934). DB 없이 문자열만으로 본다. */
class MacroSummaryTest {

    @Test
    fun `진입점 본문의 첫 주석 줄에서 앞의 샵과 공백을 뗀다`() {
        val source = """
            def attack(card: object) -> None:
                #   카드를 적에게 던진다
                click(card)
                # 두 번째 주석
        """.trimIndent()

        assertThat(firstBodyComment(source, "attack")).isEqualTo("카드를 적에게 던진다")
    }

    @Test
    fun `주석이 없으면 null 이다`() {
        val source = "def attack() -> None:\n    click(selector(\"A\"))"

        assertThat(firstBodyComment(source, "attack")).isNull()
    }

    @Test
    fun `def 앞의 주석과 문장 끝에 붙은 주석은 요약이 아니다`() {
        val source = """
            # 파일 머리말
            def attack() -> None:
                click(selector("A"))  # 끝에 붙은 설명
        """.trimIndent()

        assertThat(firstBodyComment(source, "attack")).isNull()
    }

    @Test
    fun `helper 의 주석을 진입점 요약으로 가져오지 않는다`() {
        val source = """
            def attack() -> None:
                click(selector("A"))

            def helper() -> None:
                # helper 의 설명
                click(selector("B"))
        """.trimIndent()

        assertThat(firstBodyComment(source, "attack")).isNull()
        assertThat(firstBodyComment(source, "helper")).isEqualTo("helper 의 설명")
    }

    @Test
    fun `진입점이 helper 뒤에 있어도 이름으로 찾는다`() {
        val source = """
            def helper() -> None:
                # helper 의 설명
                click(selector("B"))

            def attack() -> None:
                # 진입점의 설명
                click(selector("A"))
        """.trimIndent()

        assertThat(firstBodyComment(source, "attack")).isEqualTo("진입점의 설명")
    }

    @Test
    fun `비어 있는 주석 줄은 건너뛰고 다음 주석을 본다`() {
        val source = "def attack() -> None:\n    #\n    # 실제 설명\n    click(selector(\"A\"))"

        assertThat(firstBodyComment(source, "attack")).isEqualTo("실제 설명")
    }

    @Test
    fun `이름이 접두사만 같은 def 는 진입점이 아니다`() {
        val source = "def attack_all() -> None:\n    # 전체 공격\n    click(selector(\"A\"))"

        assertThat(firstBodyComment(source, "attack")).isNull()
    }
}
