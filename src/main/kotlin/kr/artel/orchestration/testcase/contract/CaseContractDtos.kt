package kr.artel.orchestration.testcase.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/** agent `/case-contracts` 에 보내는 TC 한 줄. */
data class ContractCaseItem(
    val id: Long,
    val scene: String,
    val precondition: String,
    val step: String,
    @param:JsonProperty("expected_value") @get:JsonProperty("expected_value") val expectedValue: String,
)

data class CaseContractRequest(
    val cases: List<ContractCaseItem>,
    /** 이미 있는 용어집. agent 는 이 이름을 다시 쓰고 없는 것만 더한다. 모양은 agent 가 정한다. */
    val terms: JsonNode,
    val about: String,
    @param:JsonProperty("project_id") @get:JsonProperty("project_id") val projectId: Long,
)

/** agent 가 돌려주는 계약 하나. 오케가 저장하는 모양 그대로다. */
data class CaseContractItem(
    val id: Long,
    val condition: JsonNode? = null,
    @param:JsonProperty("state_after") val stateAfter: String = "",
    @param:JsonProperty("assumed_before") val assumedBefore: String = "",
    /** 다른 화면으로 가는 TC 의 도착 화면(TC 가 부르는 이름). 씬 전이 TC 의 표식이다. */
    @param:JsonProperty("arrives_at") val arrivesAt: String = "",
)

data class CaseContractResponse(
    val terms: JsonNode,
    @param:JsonProperty("start_state") val startState: Map<String, String> = emptyMap(),
    val contracts: List<CaseContractItem> = emptyList(),
)

/** 한 번 정리한 결과. 화면과 로그가 같은 숫자를 본다. */
data class CaseContractRefresh(
    val asked: Int,
    val written: Int,
    val terms: Int,
)

/** agent `/case-contracts/statement` 요청. 사용자 말과 직전 질문, 지금 계약을 함께 보낸다. */
data class StatementRequest(
    val said: String,
    val question: String,
    val terms: JsonNode,
    @param:JsonProperty("start_state") @get:JsonProperty("start_state") val startState: Map<String, String>,
    val cases: List<String>,
)

data class StatementPatch(
    @param:JsonProperty("tc_id") val tcId: Long? = null,
    val op: String,
    val name: String,
    val value: String,
)

data class StatementResponse(val patches: List<StatementPatch> = emptyList())

/** QA 가 통과시킨 TC 를 실행하는 동안 바뀐 값. [observed] 는 줄마다 `id: 코드이름 앞→뒤, …` 다. */
data class ObservedContractRequest(
    val terms: JsonNode,
    @param:JsonProperty("start_state") @get:JsonProperty("start_state") val startState: Map<String, String>,
    val cases: List<String>,
    val observed: List<String>,
)
