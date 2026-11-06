package kr.artel.orchestration.tracker.client

import kr.artel.orchestration.tracker.entity.TrackerProvider
import org.springframework.stereotype.Component

/**
 * `provider` 로 구현체를 고른다.
 *
 * [IssueTrackerSyncService][kr.artel.orchestration.tracker.service.IssueTrackerSyncService] 가
 * `provider` 를 모른 채 일할 수 있는 것은 이 조회 하나 덕분이다. Jira 구현체를 빈으로 등록하면
 * 여기는 손대지 않아도 따라온다.
 */
@Component
class IssueTrackerClientRegistry(clients: List<IssueTrackerClient>) {
    private val byProvider: Map<TrackerProvider, IssueTrackerClient> =
        clients.associateBy { it.provider }

    fun of(provider: TrackerProvider): IssueTrackerClient =
        byProvider[provider] ?: throw UnsupportedTrackerException(provider.name)
}
