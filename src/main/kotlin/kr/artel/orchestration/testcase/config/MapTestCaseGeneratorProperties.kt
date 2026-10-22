package kr.artel.orchestration.testcase.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 지도 TC 생성기 설정(`artel.testcase.generator`).
 *
 * @property grouping TC 를 나누고 묶는 방식. [Grouping.LEGACY] 는 지금까지의 방식이고, [Grouping.READING] 은
 *   판독 기준으로 다시 묶는 방식이다(ARTEL-1209, POC). 같은 지도로 둘을 견주려고 나란히 둔다.
 */
@ConfigurationProperties(prefix = "artel.testcase.generator")
data class MapTestCaseGeneratorProperties(
    val grouping: Grouping = Grouping.LEGACY,
) {
    enum class Grouping {
        /** 지금까지의 방식. 판독으로 확인 못 하는 비교는 표시만 하고 남긴다. */
        LEGACY,

        /**
         * 판독 기준. 확인 못 하는 기대 항목과 비교는 지우고, 성립 불가능한 갈래는 버리고, 씬만 다른 같은 TC 는
         * 하나로 낸다([kr.artel.orchestration.testcase.generator.MapTestCaseReadingRules]).
         */
        READING,
    }
}

@Configuration
@EnableConfigurationProperties(MapTestCaseGeneratorProperties::class)
class MapTestCaseGeneratorConfig
