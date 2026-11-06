package kr.artel.orchestration.auth.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserHandleTest {

    @Test
    fun `writes a user as nickname hash userTag`() {
        assertThat(UserHandle.format("Yuni", "0042")).isEqualTo("Yuni#0042")
        assertThat(UserHandle("Yuni", "0042").toString()).isEqualTo("Yuni#0042")
    }

    @Test
    fun `splits a handle back into its two parts`() {
        assertThat(UserHandle.parse("Yuni#0042")).isEqualTo(UserHandle("Yuni", "0042"))
    }

    @Test
    fun `splits at the last hash so a nickname may contain one`() {
        assertThat(UserHandle.parse("a#b#0001")).isEqualTo(UserHandle("a#b", "0001"))
    }

    @Test
    fun `keeps the leading zeros and the length of a grown tag`() {
        assertThat(UserHandle.parse("Yuni#00042")).isEqualTo(UserHandle("Yuni", "00042"))
    }

    @Test
    fun `refuses a string that is not a handle`() {
        assertThat(UserHandle.parse("Yuni")).isNull()
        assertThat(UserHandle.parse("Yuni#")).isNull()
        assertThat(UserHandle.parse("#0042")).isNull()
        assertThat(UserHandle.parse("Yuni#004a")).isNull()
    }
}
