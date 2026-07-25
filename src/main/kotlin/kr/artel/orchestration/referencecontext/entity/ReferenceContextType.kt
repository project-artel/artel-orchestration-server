package kr.artel.orchestration.referencecontext.entity

/**
 * reference_context의 고정 타입(섹션). Agent `/extract`가 내는 game_context의 8개 상단 섹션과 1:1 대응한다.
 * 저장/조회 시에는 소문자 key(예: "mechanics")를 사용하며, 이는 game_context JSON의 필드명과 일치한다.
 */
enum class ReferenceContextType {
    OVERVIEW,
    SCREENS,
    MECHANICS,
    ENTITIES,
    PROGRESSION,
    FLOWS,
    GLOSSARY,
    MISC;

    /** game_context JSON 필드명 및 DB `type` 값(소문자). */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): ReferenceContextType? =
            entries.firstOrNull { it.key == key.lowercase() }
    }
}
