package kr.artel.orchestration.knowledge.entity

/**
 * knowledge_usage 행 하나가 **어느 경로로** 런의 컨텍스트에 들어갔는지(ARTEL-293).
 *
 * 세 경로의 신호 세기가 다르다. 밀어넣은 이웃([SEARCH_NEIGHBOR])을 안 쓰는 것은 정상이고,
 * 에이전트가 직접 요청해 놓고([EXPAND]) 안 쓴 것은 훨씬 강한 부정 신호다. 인용률의 분모가
 * 이 구분에 달려 있어, 셋을 한 통에 담으면 "지식이 쓸모 있었나"의 답이 검색 설정에 따라 흔들린다.
 *
 * **`rank`로 유추하지 않는다.** 지금은 `rank IS NULL`이 이웃과 일치하지만, 새 검색 경로가
 * 생기면 그 유추는 조용히 틀린다 — 틀린 줄 아무도 모르는 종류의 오류다. 값은 만드는 자리에서
 * [kr.artel.orchestration.knowledge.service.KnowledgeRetrieval]에 실리고, 공용 싱크인
 * `recordRetrievals`는 실린 값을 그대로 저장한다.
 *
 * DB에는 이름 그대로 저장되고 CHECK는 없다 — 이유는 V34 주석에 있다(값이 wire에서 오지 않으므로
 * 오타는 이 enum이 잡고, 경로가 늘 때마다 마이그레이션을 요구하지 않는다).
 */
enum class KnowledgeRetrievalKind {
    /** 질의에 직접 걸린 벡터 히트. `rank`가 1부터 매겨진다. */
    DIRECT,

    /** 히트에 딸려 자동으로 붙은 1홉 이웃(ARTEL-275). 에이전트가 요청한 적이 없다. */
    SEARCH_NEIGHBOR,

    /** `expand_knowledge` 툴로 에이전트가 직접 요청한 이웃. */
    EXPAND
}
