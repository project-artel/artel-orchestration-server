package kr.artel.orchestration.testscenario.entity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep

/**
 * [TestScenarioEntity](영속화 형태)와 [ScenarioDraft](도메인·전달 형태) 사이의 변환 (ARTEL-291).
 *
 * 컬럼 승격 전에는 payload 한 컬럼을 통째로 읽고 쓰면 끝이었지만, 이제 제목·설명은 컬럼이고 스텝만
 * JSONB다. 그 조립·분해가 읽기 4곳(단건 조회 2·목록 요약·런 컨텍스트)과 쓰기 3곳(생성·자동저장·승인)에
 * 흩어지므로 여기 한 쌍으로 모은다. 확장 함수만 두고 클래스나 빈은 만들지 않는다 — 상태가 없고
 * [ObjectMapper]는 호출부가 이미 들고 있다.
 */

/** 엔티티의 세 컬럼을 [ScenarioDraft]로 조립한다. */
fun TestScenarioEntity.toDraft(objectMapper: ObjectMapper): ScenarioDraft =
    ScenarioDraft(
        title = title,
        description = description,
        steps = objectMapper.readValue<List<ScenarioStep>>(steps.asString()),
    )

/** [draft]를 세 컬럼으로 분해해 얹은 사본을 돌려준다. 저장은 호출부가 한다. */
fun TestScenarioEntity.withDraft(draft: ScenarioDraft, objectMapper: ObjectMapper): TestScenarioEntity =
    copy(
        title = draft.title,
        description = draft.description,
        steps = Json.of(objectMapper.writeValueAsString(draft.steps)),
    )
