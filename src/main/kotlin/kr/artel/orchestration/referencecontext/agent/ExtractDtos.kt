package kr.artel.orchestration.referencecontext.agent

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.referencecontext.dto.GameContextPayload

/**
 * Agent `POST /extract` 요청. presigned GET URL과 포맷 힌트를 넘기면 Agent가 문서를 받아
 * 구조화된 game_context를 뽑는다. Agent는 자격증명 없이 익명 GET만 하므로 [sourceUrl]은
 * 반드시 서명된(presigned) URL이어야 한다.
 *
 * @property sourceUrl presigned GET URL (X-Amz-Signature 포함)
 * @property filename 포맷 판별용 파일명(.pdf/.txt/.md) — URL과 별개의 힌트
 * @property model 선택. null이면 직렬화에서 빠지고 Agent 기본 모델을 쓴다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExtractRequest(
    @JsonProperty("source_url") val sourceUrl: String,
    val filename: String,
    val model: String? = null
)

/**
 * Agent `/extract` 200 응답. 본문의 `game_context`를 그대로 [GameContextPayload]로 받는다.
 *
 * @property filename Agent가 판별에 쓴 파일명(에코)
 * @property gameContext 8섹션 고정 요약본
 */
data class ExtractResponse(
    val filename: String? = null,
    @JsonProperty("game_context") val gameContext: GameContextPayload
)
