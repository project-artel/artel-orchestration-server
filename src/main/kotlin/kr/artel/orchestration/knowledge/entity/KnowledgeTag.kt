package kr.artel.orchestration.knowledge.entity

/**
 * knowledge 항목의 성질 태그 — "이 정보가 어떤 종류인가"의 1차 이정표(검색 전 필터가 됨).
 *
 * Phase 1 최소 집합만 정의한다(확장은 후속에서 심화 논의). 우리가 사전 정의하고 Agent가 이 토큰을 보낸다.
 * - [CONTROL] : 조작 관련(입력/조작 방식 등).
 * - [INFO]    : 정보 관련(수치/규칙/설명 등).
 * - [MISC]    : 위 분류에 안 맞는 기타.
 *
 * 저장은 이름 그대로 VARCHAR + CHECK. [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시 매칭).
 */
enum class KnowledgeTag {
    CONTROL,
    INFO,
    MISC;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        fun fromWire(value: String?): KnowledgeTag? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}
