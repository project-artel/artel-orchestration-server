package kr.artel.orchestration.tracker.entity

/**
 * 결함을 내보낼 수 있는 외부 이슈 tracker.
 *
 * GitHub 이 첫 구현체일 뿐이고 Jira 가 다음 후보다. 그래서 이 값은 테이블 이름도 endpoint 경로도
 * 아니고 **컬럼에 담기는 값**이다. tracker 를 하나 더 붙이는 비용은 여기 상수 하나, 마이그레이션의
 * CHECK 한 줄, 그리고 [kr.artel.orchestration.tracker.client.IssueTrackerClient] 구현체 하나다.
 */
enum class TrackerProvider {
    GITHUB;

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        /** 요청이 실어 온 문자열을 값으로. 모르는 이름이면 null 이고, 호출부가 400 으로 옮긴다. */
        fun parse(value: String?): TrackerProvider? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 결함 하나가 외부 tracker 로 나가는 과정의 `sync_state`.
 *
 * - [PENDING] : 누군가 이 결함을 `claim` 해 내보내는 중이다. **행의 존재와 이 값이 곧 lock 을
 *   대신한다** — 조건부 INSERT 가 이 값을 만들지 못하면 다른 요청이 이미 가져간 것이다.
 *   정확히는 lock 이 아니라 **만료되는 lease** 다: `TrackerProperties.claimStaleAfter` 가 지나면
 *   다른 요청이 이 값을 다시 `claim` 할 수 있다. 그러지 않으면 내보내는 도중 죽은 프로세스가 남긴
 *   행을 아무도 되살리지 못한다.
 * - [SYNCED]  : 저쪽에 이슈가 생겼고 `external_key` 가 그것을 가리킨다. 다시 `claim` 되지 않는다.
 *   (외부 호출 성공과 `markSynced` 사이에 프로세스가 죽으면 이 값에 닿지 못한 채 `PENDING` 으로
 *   남는다. 그 잔여 창은 `IssueTrackerLinkRepository.claim` 주석에 적혀 있다.)
 * - [FAILED]  : 내보내기가 실패했다. 사람이 수동 endpoint 로 다시 시도할 수 있다.
 */
enum class TrackerSyncState {
    PENDING,
    SYNCED,
    FAILED
}
