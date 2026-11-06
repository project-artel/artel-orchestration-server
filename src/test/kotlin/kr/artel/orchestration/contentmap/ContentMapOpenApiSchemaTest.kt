package kr.artel.orchestration.contentmap

import io.swagger.v3.core.converter.ModelConverters
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.contentmap.dto.MacroSummaryResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContentMapOpenApiSchemaTest {
    @Test
    fun `OpenAPI includes macro property descriptions`() {
        val schema = ModelConverters.getInstance().read(MacroSummaryResponse::class.java)
            .getValue("MacroSummaryResponse")

        assertThat(schema.properties.getValue("name").description)
            .isEqualTo("진입점 `def` 의 이름. 같은 빌드 안에서 유일하다")
        assertThat(schema.properties.getValue("updatedAt").description)
            .isEqualTo("정의를 마지막으로 고친 시각")
    }

    @Test
    fun `OpenAPI includes nested condition property descriptions`() {
        val schemas = ModelConverters.getInstance().read(ConditionNodeResponse.Gesture::class.java)
        val schema = schemas.values.single { it.properties?.containsKey("input") == true }

        assertThat(schema.properties.getValue("input").description)
            .isEqualTo("`key:RightArrow (down)` 꼴. 근거가 렌더한 문자열 그대로다")
    }
}
