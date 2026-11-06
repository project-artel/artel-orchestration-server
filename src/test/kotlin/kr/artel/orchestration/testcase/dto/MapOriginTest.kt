package kr.artel.orchestration.testcase.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** metadata 의 `origin` 만 훑는 [isMapOrigin] 이 예전 트리 읽기와 같은 답을 내는가(ARTEL-1212). */
class MapOriginTest {
    @Test
    fun `finds the map origin wherever it sits at the top level`() {
        assertThat(isMapOrigin("""{"origin":"content-map","case_key":"a"}""")).isTrue()
        assertThat(isMapOrigin("""{"source":{"origin":"elsewhere","list":[1,{"origin":"x"}]},"origin":"content-map"}""")).isTrue()
    }

    @Test
    fun `is not fooled by a nested origin or another value`() {
        assertThat(isMapOrigin("""{"source":{"origin":"content-map"}}""")).isFalse()
        assertThat(isMapOrigin("""{"origin":"excel"}""")).isFalse()
        assertThat(isMapOrigin("""{"origin":1}""")).isFalse()
    }

    @Test
    fun `treats empty or unreadable metadata as not from the map`() {
        assertThat(isMapOrigin("{}")).isFalse()
        assertThat(isMapOrigin("[]")).isFalse()
        assertThat(isMapOrigin("not json")).isFalse()
        assertThat(isMapOrigin("")).isFalse()
    }
}
