package kr.artel.orchestration.project.event

/**
 * 프로젝트 변경 stream 이 알리는 데이터 종류. home 은 이 값을 보고 해당 목록을 다시 읽는다.
 *
 * 이벤트는 무엇이 바뀌었는지만 싣고 바뀐 내용은 싣지 않는다. 내용은 각 목록의 GET 이 주고,
 * 그 GET 이 이미 권한과 필터를 적용한다.
 */
enum class ProjectChangeTopic {
    /** 게임 인스턴스 목록과 각 인스턴스의 `connected`. SDK WebSocket 연결과 끊김도 여기에 든다. */
    GAME_INSTANCES,

    /** 게임 빌드 목록. */
    GAME_BUILDS,

    /** 빌드의 agent macro 와 프로젝트의 developer macro. */
    MACROS,

    /** QA issue. */
    ISSUES,

    /** `PRODUCTION` scope 의 knowledge 와 knowledge graph. */
    KNOWLEDGE,

    /** test case, test case 폴더와 폴더 안의 항목. */
    TEST_CASES,

    /** 테스트 런과 시나리오 구성 및 커버리지. */
    TEST_RUNS,

    /** Artel Bot 이 런의 시나리오를 고치는 동안의 잠금과 풀림(ARTEL-1264). */
    RUN_LOCKS,

    /** LLM 사용량 기록. */
    USAGE,
}
