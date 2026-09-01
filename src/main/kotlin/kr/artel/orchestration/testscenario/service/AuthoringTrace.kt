package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.config.AuthoringTraceProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 저작 한 판을 시간 순서대로 받아 적는다(ARTEL-650).
 *
 * ## 왜 필요한가
 *
 * 저작은 답 하나가 여러 손을 거치며 모양을 바꾼다 — 지도가 계산한 구조가 문장이 되고, 모델이
 * 그 문장을 옮겨 적고, 코드가 그 위에 다시 도장을 찍는다. 어느 손에서 뒤집혔는지가 곧 원인인데,
 * 지금까지는 그것을 되짚을 방법이 없었다. 실측 하나를 얻으려고 서버를 다시 띄우고 로그를 긁어
 * 판을 재구성했고, 그 과정에서 **틀린 자리를 두 번 지나쳤다**(런 213: 고친 코드가 도는데도
 * 결과가 안 변한 이유가 한 층 위에 있었다).
 *
 * ## 두 지점만 잡으면 전부 남는다
 *
 * 에이전트는 스스로 계산하지 않는다. 네 가지 도구가 전부 이쪽에 묻고 답을 문장으로 바꿀 뿐이라,
 * **에이전트를 계측하지 않아도** 오가는 것이 전부 이 서버를 지난다. 그래서 받는 자리와 보내는
 * 자리 둘에 한 줄씩 두면 경계의 왕복이 통째로 남는다. 나머지는 이 서버가 그 답에 하는 일이다.
 *
 * 모델 속 생각은 안 남는다. 그건 여기서 볼 수 있는 것이 아니고, 볼 필요도 없었다 — 오늘까지
 * 나온 원인은 전부 경계의 이쪽이었다.
 *
 * ## 형식
 *
 * 판 하나가 파일 하나다. 사람이 위에서 아래로 읽는 것이 목적이라 한 줄에 한 사건을 적고, 긴 것
 * (케이스 전량 목록·모델 답 원문)은 옆 파일로 빼고 그 이름만 남긴다 — 본문에 그대로 부으면
 * 읽을 수 없게 된다.
 *
 * **저작을 막지 않는다.** 기록이 실패해도 삼킨다. 되짚으려고 둔 것이 저작을 멎게 하면 안 된다.
 */
@Component
class AuthoringTrace(private val properties: AuthoringTraceProperties) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()

    /** 한 사건. [detail] 은 여러 줄이어도 되고, 들여써서 붙는다. */
    fun record(runId: Long, event: String, detail: String? = null) {
        if (!properties.enabled) return
        runCatching {
            val body = buildString {
                append(LocalDateTime.now().format(clock))
                append("  ")
                append(event)
                detail?.takeIf { it.isNotBlank() }?.let { text ->
                    text.trimEnd().lines().forEach { append("\n              ").append(it) }
                }
                append("\n")
            }
            synchronized(lock) {
                val file = fileOf(runId)
                Files.writeString(file, body, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }.onFailure { logger.debug("저작 기록 실패 [runId={}] {}", runId, it.message) }
    }

    /**
     * 본문에 담기 너무 큰 것을 옆 파일로 뺀다. 돌려주는 것은 **본문에 적을 한 줄** — 파일 이름과
     * 크기다. 꺼져 있으면 빈 문자열이라 부르는 쪽이 분기하지 않아도 된다.
     */
    fun blob(runId: Long, name: String, content: String): String {
        if (!properties.enabled) return ""
        return runCatching {
            val file = dir().resolve("run-$runId-$name")
            Files.writeString(file, content)
            "→ ${file.fileName} (${content.length}자)"
        }.getOrElse {
            logger.debug("저작 기록 첨부 실패 [runId={}] {}", runId, it.message)
            ""
        }
    }

    private fun fileOf(runId: Long): Path = dir().resolve("run-$runId.log")

    private fun dir(): Path = Path.of(properties.dir).also { Files.createDirectories(it) }
}
