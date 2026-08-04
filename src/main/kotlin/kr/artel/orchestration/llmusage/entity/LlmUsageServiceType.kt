package kr.artel.orchestration.llmusage.entity

/**
 * LLM 호출이 어느 기능에서 났는지. 동시에 `reference_id`가 어느 테이블의 id인지도 정한다
 * (다형 참조라 이 값 없이는 reference_id를 해석할 수 없다):
 *
 * - [QA_RUN]          : `qa_try.id`
 * - [SCENARIO]        : `test_scenario.id`
 * - [KNOWLEDGE_QUERY] : `project.id`
 * - [GAME_CONTEXT]    : `project_document.id`
 * - [EMBEDDING]       : `project.id`
 *
 * 이름 그대로 VARCHAR + CHECK로 저장한다(IssueSeverity와 동일). DTO를 이 enum으로 받아,
 * 계약에 없는 값은 DB CHECK(500)까지 가지 않고 요청 파싱 단계에서 400으로 떨어지게 한다.
 */
enum class LlmUsageServiceType {
    QA_RUN,
    SCENARIO,
    KNOWLEDGE_QUERY,
    GAME_CONTEXT,
    EMBEDDING
}
