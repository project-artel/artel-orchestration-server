package kr.artel.orchestration.contentmap.scan

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.dto.LastScanResponse
import kr.artel.orchestration.contentmap.dto.StartContentMapScanResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 스캔 응답의 **wire 모양**을 못 박는다.
 *
 * ARTEL-489(home) 가 이 JSON 을 보고 화면을 만들고 있다. 칸 이름이나 상태 어휘가 여기서 바뀌면
 * 상대편은 컴파일 오류 없이 조용히 못 알아듣는다 — `QaAgentEnvelopeTest` 가 같은 이유로 있다.
 *
 * 특히 [ScanState] 의 세 값이 그대로 문자열로 나가는 것이 요점이다. 화면이 버튼 상태를 그 값으로
 * 가르므로, 직렬화가 서수(0·1·2)로 바뀌면 화면이 통째로 어긋난다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScanResponseContractTest {

    @Autowired private lateinit var objectMapper: ObjectMapper

    /** 202 가 돌려주는 것. **어느 인스턴스가 받았는지**와 무엇을 기다리면 되는지를 말한다. */
    @Test
    fun `스캔 요청 응답의 모양`() {
        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                StartContentMapScanResponse(
                    gameInstanceId = 42,
                    gameInstanceName = "Editor - MacBook",
                    state = ScanState.REQUESTED,
                    requestedAt = Instant.parse("2026-08-21T07:12:33.412Z"),
                )
            )
        )

        assertThat(json.fieldNames().asSequence().toList())
            .containsExactly("gameInstanceId", "gameInstanceName", "state", "requestedAt")
        assertThat(json["gameInstanceId"].asLong()).isEqualTo(42)
        assertThat(json["gameInstanceName"].asText()).isEqualTo("Editor - MacBook")
        // 서수가 아니라 이름이다.
        assertThat(json["state"].asText()).isEqualTo("REQUESTED")
    }

    /**
     * 조회 응답에 얹히는 `lastScan`. **화면이 폴링하며 보는 값이다.**
     *
     * `ingestedDocuments` 가 0 일 수 있다 — 게임은 성공이라 답했는데 앉힐 문서가 없었다는 뜻이고,
     * 그 경우와 정상 성공을 화면이 갈라야 하므로 수를 싣는다.
     */
    @Test
    fun `마지막 스캔 상태의 모양`() {
        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                LastScanResponse.of(
                    ScanStatus(
                        gameBuildId = 7,
                        gameInstanceId = 42,
                        gameInstanceName = "Editor - MacBook",
                        state = ScanState.FAILED,
                        requestedAt = Instant.parse("2026-08-21T07:12:33.412Z"),
                        finishedAt = Instant.parse("2026-08-21T07:12:51.907Z"),
                        ingestedDocuments = 0,
                        error = "릴리스 빌드에서는 스캔할 수 없습니다.",
                    )
                )
            )
        )

        assertThat(json.fieldNames().asSequence().toList()).containsExactly(
            "state", "gameInstanceId", "gameInstanceName", "requestedAt",
            "finishedAt", "ingestedDocuments", "error",
        )
        assertThat(json["state"].asText()).isEqualTo("FAILED")
        assertThat(json["ingestedDocuments"].asInt()).isZero()
        assertThat(json["error"].asText()).isEqualTo("릴리스 빌드에서는 스캔할 수 없습니다.")
        // gameBuildId 는 싣지 않는다 — 이 응답은 이미 그 빌드의 조회 결과 안에 있다.
        assertThat(json.has("gameBuildId")).isFalse()
    }
}
