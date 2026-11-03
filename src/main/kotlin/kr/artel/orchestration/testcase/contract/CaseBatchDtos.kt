package kr.artel.orchestration.testcase.contract

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * TC 를 계약까지 한 번에 넣는 요청(MCP 의 `save_test_cases`). 사용자의 AI 가 게임 코드를 보고 계약을 채우는 입구다.
 *
 * 계약 칸은 선택이다. 비우면 지금처럼 AI 가 글에서 뽑는다. 채우면 그 값을 출처 `user` 로 남겨 AI 추출이 덮지 않는다.
 */
data class CaseBatchRequest(
    /** 새로 쓰는 상태 이름. 이미 용어집에 있는 이름은 건너뛴다 — 이름은 `list` 가 돌려준 것을 다시 쓴다. */
    val terms: List<CaseTermInput> = emptyList(),
    val cases: List<CaseBatchItem> = emptyList(),
    /** 새 TC 를 넣을 버전. 비우면 프로젝트의 가장 최근 버전이다 — 버전이 정해진 TR 의 저작은 그 버전의 TC 만 본다. */
    val gameBuildId: String? = null,
)

data class CaseTermInput(
    val name: String,
    /** has: 있다/없다. value: 고른 값 하나(options 중). counter: 쌓이는 수. */
    val kind: String,
    val meaning: String = "",
    val options: List<String> = emptyList(),
    /** 새 게임을 시작할 때 값. has 는 `1`/`0`, value 는 options 중 하나, counter 는 수. 모르면 비운다. */
    val start: String? = null,
)

data class CaseBatchItem(
    /** 고칠 TC. 비우면 새로 만든다. */
    val testCaseId: String? = null,
    val scene: String,
    val precondition: String? = null,
    val step: String,
    val expectedValue: String,
    /** 이 TC 를 하기 전에 성립해야 하는 것. `검 == 1`, `동전 >= 30`, `낮밤 == 밤`. */
    val needs: List<String>? = null,
    /** 하고 나면 남는 것. `등불=1`, `동전=+20`, `낮밤=밤`. */
    val leaves: List<String>? = null,
    /** 하고 나면 다른 화면으로 가면 그 화면(씬 전이 TC). `광장`. */
    val arrivesAt: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class CaseBatchResponse(
    val created: List<String>,
    val updated: List<String>,
    val rejected: List<CaseBatchRejection>,
    val addedTerms: List<String>,
)

data class CaseBatchRejection(val index: Int, val reason: String)

/** 계약까지 붙인 TC 목록과 용어집(MCP 의 `list_test_cases`). */
data class CaseContractList(
    val terms: com.fasterxml.jackson.databind.JsonNode,
    val startState: Map<String, String>,
    val cases: List<CaseContractRow>,
)

data class CaseContractRow(
    val testCaseId: String,
    val scene: String,
    val precondition: String?,
    val step: String,
    val expectedValue: String,
    val needs: List<String>,
    val leaves: List<String>,
    /** ai: 글에서 뽑음. user: 사람(또는 사용자 AI)이 적음. observed: QA 가 봄. 없으면 아직 계약이 없다. */
    val contractSource: String?,
    /** 다른 화면으로 가는 TC 면 도착 화면. */
    val arrivesAt: String? = null,
)
