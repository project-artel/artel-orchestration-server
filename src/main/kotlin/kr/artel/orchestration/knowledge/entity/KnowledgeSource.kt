package kr.artel.orchestration.knowledge.entity

/**
 * knowledge 항목의 출처.
 *
 * 입력부가 전부 Agent라 테이블은 하나로 통합하고, 신뢰도/성질 구분은 이 값(+`tag`)으로 한다.
 * - [DOCS]: 업로드한 참고자료(문서)에서 추출한 요약.
 * - [QA]  : QA 실행(qa_try) 중 Agent가 관측/추론한 정보.
 *
 * 저장은 이름 그대로 VARCHAR + CHECK. [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시 매칭).
 */
enum class KnowledgeSource {
    DOCS,
    QA;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        /** Agent가 "docs"/"qa"처럼 소문자로 보내도 받도록 대소문자 무시로 파싱한다. */
        fun fromWire(value: String?): KnowledgeSource? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}
