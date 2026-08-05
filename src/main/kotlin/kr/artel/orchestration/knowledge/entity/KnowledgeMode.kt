package kr.artel.orchestration.knowledge.entity

/**
 * 한 QA 런에게 지식창고를 얼마나 열어 줄지(ARTEL-256). `qa_try.run_config.knowledge_mode`에 남는다.
 *
 * 스코프([KnowledgeScope])만으로는 "지식이 실제로 도움이 되는가"를 물을 수 없다. 스코프는 arm끼리
 * 서로의 지식을 못 보게 할 뿐, 지식 자체를 없애지는 않기 때문이다. 그 질문에 답하려면 읽기와
 * 쓰기를 끌 수 있어야 한다.
 *
 * - [LEARNING] — 읽고 쓴다. 기본값이고, 이 값이면 ARTEL-256 이전과 동작이 같다.
 * - [FROZEN]   — 읽기만. 쓰기 프레임(배치 인입·생성·수정·삭제)은 거부된다. 지식창고를 그 런이
 *                바꾸지 못하게 고정하고, 같은 출발점에서 여러 arm을 돌릴 때 쓴다.
 * - [OFF]      — 검색이 언제나 빈 결과다. 쓰기도 막힌다. "지식 없이 돌면 얼마나 하나"의 대조군.
 *
 * ## Agent가 아니라 서버에서 막는 이유
 *
 * Agent 쪽 프롬프트나 도구 목록을 arm마다 바꾸면 달라진 변수가 "지식 가용성" 하나가 아니게 된다.
 * 서버에서 막으면 **arm마다 Agent 프롬프트가 바이트 단위로 동일**하고, 남는 차이는 검색 결과가
 * 비어 있다는 것뿐이다. Agent 쪽은 이미 그것을 견딘다 — 빈 검색 결과는 정상 응답으로 처리하고
 * (`KnowledgeSearchResultPayload`), 쓰기는 애초에 응답을 기다리지 않는 단방향이다(`MessageType`).
 *
 * 저장은 소문자 wire 토큰(`learning`/`frozen`/`off`)이다. run_config는 Agent가 준 JSON과 한 객체를
 * 이루므로 그쪽 표기(snake_case 소문자)를 따른다.
 */
enum class KnowledgeMode(val wire: String) {
    LEARNING("learning"),
    FROZEN("frozen"),
    OFF("off");

    /** 이 모드에서 검색이 실제로 지식창고를 읽는가. */
    val readable: Boolean get() = this != OFF

    /** 이 모드에서 런이 지식창고에 쓸 수 있는가. */
    val writable: Boolean get() = this == LEARNING

    companion object {
        /** `run_config`에 값이 없는 런(이 변경 이전 런 포함)은 지금까지처럼 읽고 쓴다. */
        val DEFAULT = LEARNING

        val WIRE_NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.wire }

        fun fromWire(value: String?): KnowledgeMode? =
            value?.trim()?.lowercase()?.let { normalized -> entries.firstOrNull { it.wire == normalized } }
    }
}
