package kr.artel.orchestration.game.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InstanceKeyGeneratorTest {

    private val generator = InstanceKeyGenerator()

    @Test
    fun `emits four dash-separated groups of five characters`() {
        val key = generator.generate()

        assertThat(key).matches("[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{5}")
    }

    @Test
    fun `omits the characters that are confused when read`() {
        val keys = (1..200).map { generator.generate() }

        // I/1, L/1, O/0, U/V가 섞이면 화면의 키와 붙여넣은 키를 눈으로 대조할 수 없다.
        assertThat(keys).allSatisfy { key ->
            assertThat(key.replace("-", "")).doesNotContainAnyWhitespaces()
            assertThat(key).doesNotContain("I")
            assertThat(key).doesNotContain("L")
            assertThat(key).doesNotContain("O")
            assertThat(key).doesNotContain("U")
        }
    }

    @Test
    fun `does not repeat itself`() {
        val keys = (1..500).map { generator.generate() }

        assertThat(keys.toSet()).hasSize(keys.size)
    }
}
