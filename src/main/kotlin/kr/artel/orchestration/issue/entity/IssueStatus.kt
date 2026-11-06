package kr.artel.orchestration.issue.entity

/**
 * 이슈에 사람이 남기는 유일한 상태.
 *
 * - [OPEN]     : 아직 처리되지 않음. Agent가 보고한 직후의 상태다.
 * - [RESOLVED] : 사람이 처리했다고 표시함. `resolved_at`/`resolved_by`가 함께 채워진다.
 *
 * 저장은 [IssueSeverity]와 같이 이름 그대로 VARCHAR + CHECK다. [NAMES]는 조회 필터로 들어온
 * 값을 검증할 때 쓴다.
 */
enum class IssueStatus {
    OPEN,
    RESOLVED;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }
    }
}
