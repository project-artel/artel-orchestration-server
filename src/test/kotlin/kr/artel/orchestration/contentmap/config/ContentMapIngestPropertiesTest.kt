package kr.artel.orchestration.contentmap.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 적재 배치 설정의 제약은 기동 시점에 깨져야 한다.
 *
 * 이 배치는 씬·기능 행을 앉히고 이번 문서에 없는 기능을 내린다. 잘못된 값이 런타임 한참 뒤에
 * "왜인지 큐가 안 빈다"로만 드러나면 원인을 찾는 데 오래 걸린다.
 */
class ContentMapIngestPropertiesTest {

    @Test
    fun `기본값은 배치를 꺼 둔다`() {
        val properties = ContentMapIngestProperties()

        // 적재는 이번 문서에 없는 기능을 내린다. 배포의 부수 효과로 그것이 시작되면 안 된다.
        assertThat(properties.enabled).isFalse()
    }

    @Test
    fun `기본 상한이 있어 깨진 문서가 큐를 영영 점유하지 못한다`() {
        val properties = ContentMapIngestProperties()

        // 상한이 없으면 파싱에서 죽는 문서 하나가 매 tick 마다 1.4 MB 를 다시 읽는다.
        assertThat(properties.maxAttempts).isGreaterThan(0)
    }

    @Test
    fun `0 이하의 배치 크기나 시도 상한은 거절한다`() {
        assertThatThrownBy { ContentMapIngestProperties(batchSize = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("batch-size")

        // 상한이 0이면 어떤 문서도 집히지 않는다. 배치가 도는데 아무 일도 일어나지 않는 상태다.
        assertThatThrownBy { ContentMapIngestProperties(maxAttempts = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("max-attempts")
    }

    @Test
    fun `0 이하의 tick 간격은 거절한다`() {
        // fixedDelay 가 0이면 tick 이 쉬지 않고 붙어 돌아 스케줄러 스레드를 통째로 문다.
        assertThatThrownBy { ContentMapIngestProperties(intervalMillis = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("interval-millis")
    }
}
