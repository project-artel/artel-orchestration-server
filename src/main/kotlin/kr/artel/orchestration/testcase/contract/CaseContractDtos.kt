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
