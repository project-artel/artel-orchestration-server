package kr.artel.orchestration.issue.entity

/**
 * Agent가 이슈에 매기는 심각도 사다리.
 *
 * 선언 순서가 곧 심각도 순위다(위가 가장 높음). 잘 알려진 QA 등급 체계를 따른다:
 *
 * - [BLOCKER]  : 게임이 아예 진행 불가 — QA 자체를 더 못 이어간다.
 * - [CRITICAL] : 핵심 기능이 깨졌고 우회 수단이 없다.
 * - [MAJOR]    : 중요한 기능이 깨졌으나 우회 수단은 있다.
 * - [MINOR]    : 영향이 작거나 국소적인 결함.
 * - [TRIVIAL]  : 외형/문구 등 사소한 결함.
 *
 * 저장은 이름 그대로 VARCHAR + CHECK로 한다(순위 계산에 ordinal을 의존하지 않으므로 상수
 * 순서를 바꿔도 저장값은 안전하다). [NAMES]는 인바운드 프레임의 severity 값을 검증할 때 쓴다.
 */
enum class IssueSeverity {
    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    TRIVIAL;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }
    }
}
