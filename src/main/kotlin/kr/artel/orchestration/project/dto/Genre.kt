package kr.artel.orchestration.project.dto

/**
 * 프로젝트 장르. 닫힌 집합이라 이후 에이전트 파이프라인이 장르별로 동작을 나눌 수 있고,
 * [OTHER]가 있어 목록에 없는 장르 때문에 프로젝트 생성이 막히지 않는다.
 */
enum class Genre {
    ACTION,
    RPG,
    PUZZLE,
    SIMULATION,
    STRATEGY,
    SPORTS,
    SHOOTER,
    CASUAL,
    OTHER
}
