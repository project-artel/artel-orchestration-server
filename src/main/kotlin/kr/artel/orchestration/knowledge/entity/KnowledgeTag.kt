package kr.artel.orchestration.knowledge.entity

/**
 * knowledge 항목의 **내재적 성질(topic)** 태그 — "이 지식이 어떤 종류인가"의 1차 이정표.
 * 검색 전 하드 필터가 되고, 각 소비자(시나리오 작성 / QA 진행 / 보고서 / Issue 작성)는
 * "내가 어떤 tag를 뽑을지"를 질의 쪽에서 매핑한다. **쓰임새(purpose)로 tag를 나누지 않는다** —
 * 하나의 지식은 여러 태스크가 공유(다대다)하므로 purpose는 문서가 아니라 질의에 둔다.
 *
 * 단일축(topic) enum, 항목당 1개. 경계가 자명한 최소 집합이라야 Agent 분류 오류가 준다.
 * 우리가 사전 정의하고 Agent가 이 토큰을 보낸다.
 * - [CONTROL]   : 입력·조작 방식(이동/버튼/액션 등). "어떻게 조작하나".
 * - [RULE]      : 시스템·규칙·수치·제약. "게임이 어떻게 굴러가나".
 * - [OBJECTIVE] : 목표·성공/실패 조건·진행. "무엇이 일어나야 하나" — QA 판정·Issue 기대동작의 핵심.
 * - [UI]        : 화면·HUD·메뉴 요소.
 * - [MISC]      : 위 분류에 안 맞는 기타(버리지 않고 담아 검색 대상엔 남긴다).
 *
 * 향후 한 축으로 부족하면 값을 늘리기보다 **직교 facet 컬럼**을 새로 추가한다(YAGNI로 지금은 단일축).
 * 저장은 이름 그대로 VARCHAR + CHECK. [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시 매칭).
 */
enum class KnowledgeTag {
    CONTROL,
    RULE,
    OBJECTIVE,
    UI,
    MISC;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        fun fromWire(value: String?): KnowledgeTag? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}
