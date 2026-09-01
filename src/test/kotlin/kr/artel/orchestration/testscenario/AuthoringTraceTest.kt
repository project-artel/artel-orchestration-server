package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.config.AuthoringTraceProperties
import kr.artel.orchestration.testscenario.service.AuthoringTrace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AuthoringTraceTest {

    @Test
    fun `한 판은 파일 하나이고 사건은 시간 순서로 쌓인다`(@TempDir dir: Path) {
        val trace = AuthoringTrace(AuthoringTraceProperties(enabled = true, dir = dir.toString()))

        trace.record(7, "판을 연다", "케이스 42건")
        trace.record(7, "답을 냈다")
        trace.record(8, "다른 판")

        val text = Files.readString(dir.resolve("run-7.log"))
        assertThat(text.lines().filter { it.isNotBlank() }).hasSize(3)
        assertThat(text).contains("판을 연다").contains("케이스 42건").contains("답을 냈다")
        // 판이 다르면 파일이 다르다 — 한 파일을 위에서 아래로 읽는 것이 이 기록의 전부다.
        assertThat(Files.readString(dir.resolve("run-8.log"))).doesNotContain("판을 연다")
    }

    /** 긴 것을 본문에 부으면 읽을 수 없게 된다. 옆에 두고 이름만 남긴다. */
    @Test
    fun `긴 것은 옆 파일로 빠지고 본문에는 이름만 남는다`(@TempDir dir: Path) {
        val trace = AuthoringTrace(AuthoringTraceProperties(enabled = true, dir = dir.toString()))

        val line = trace.blob(7, "cases.json", "[".repeat(500))

        assertThat(line).contains("run-7-cases.json").contains("500자")
        assertThat(Files.readString(dir.resolve("run-7-cases.json"))).hasSize(500)
    }

    /**
     * **꺼져 있으면 아무것도 남기지 않는다.** 부르는 쪽이 분기하지 않도록 첨부는 빈 문자열을
     * 돌려준다 — 그래야 기록을 켜고 끄는 것이 저작 코드의 모양을 바꾸지 않는다.
     */
    @Test
    fun `꺼져 있으면 파일도 만들지 않는다`(@TempDir dir: Path) {
        val off = dir.resolve("없어야 한다")
        val trace = AuthoringTrace(AuthoringTraceProperties(enabled = false, dir = off.toString()))

        trace.record(7, "판을 연다")

        assertThat(trace.blob(7, "cases.json", "무엇이든")).isEmpty()
        assertThat(Files.exists(off)).isFalse()
    }

    /** 되짚으려고 둔 것이 저작을 멎게 하면 안 된다. */
    @Test
    fun `적을 수 없어도 던지지 않는다`(@TempDir dir: Path) {
        val blocked = Files.createFile(dir.resolve("파일이라 폴더가 못 된다"))
        val trace = AuthoringTrace(AuthoringTraceProperties(enabled = true, dir = blocked.toString()))

        trace.record(7, "판을 연다")

        assertThat(trace.blob(7, "cases.json", "무엇이든")).isEmpty()
    }
}
