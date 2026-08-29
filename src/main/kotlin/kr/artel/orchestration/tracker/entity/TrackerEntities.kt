package kr.artel.orchestration.tracker.entity

import kr.artel.orchestration.issue.entity.IssueSeverity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 한 프로젝트가 어느 tracker 의 어느 저장소를 쓰는지.
 *
 * 컬럼 이름이 전부 `provider` 중립인 이유는 `V69__link_projects_and_issues_to_trackers.sql` 에 적혀
 * 있다. GitHub 에서 [externalWorkspace] 는 owner 이고 [installationRef] 는 App 의 installation id 다.
 *
 * [installationRef] 가 null 일 수 있는 이유가 둘이다: `installation` callback 이 저장소 선택보다 먼저 오고,
 * GitHub 밖의 tracker 에는 `installation` 이라는 개념이 없다.
 */
@Table("project_tracker_link")
data class ProjectTrackerLinkEntity(
    @Id val id: Long? = null,
    @Column("project_id") val projectId: Long,
    val provider: String,
    @Column("external_workspace") val externalWorkspace: String? = null,
    @Column("external_repository") val externalRepository: String? = null,
    @Column("installation_ref") val installationRef: String? = null,
    @Column("auto_sync_severities") val autoSyncSeverities: String = DEFAULT_AUTO_SYNC_SEVERITIES,
    @Column("connected_by") val connectedBy: Long? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null
) {
    /** 내보낼 저장소가 정해졌는지. `installation` 만 붙고 저장소를 아직 고르지 않은 행과 구분한다. */
    val hasTarget: Boolean
        get() = !externalWorkspace.isNullOrBlank() && !externalRepository.isNullOrBlank()

    /**
     * 자동 `sync` 를 거는 severity 기준.
     *
     * 저장은 쉼표 구분 문자열이고 읽을 때 열거로 되돌린다. 모르는 값은 조용히 버린다 — 이 값을
     * 쓰는 경로는 전부 검증을 통과한 입력만 저장하므로, 여기서 만나는 미지의 값은 severity 사다리가
     * 바뀐 뒤에 남은 옛 값뿐이고, 그것 때문에 `sync` 전체를 실패시킬 이유가 없다.
     */
    val autoSyncSeverityLadder: Set<IssueSeverity>
        get() = autoSyncSeverities.split(',')
            .mapNotNull { name -> IssueSeverity.entries.firstOrNull { it.name == name.trim() } }
            .toSet()

    companion object {
        /** 기본 자동 `sync` 기준. 마이그레이션의 컬럼 기본값과 같이 움직여야 한다. */
        val DEFAULT_AUTO_SYNC_SEVERITIES: String =
            listOf(IssueSeverity.BLOCKER, IssueSeverity.CRITICAL).joinToString(",") { it.name }
    }
}

/**
 * 결함 하나가 외부 tracker 에 남긴 흔적.
 *
 * `issue` 테이블에 컬럼을 더하지 않는 이유는 마이그레이션 주석에 있다. [syncState] 는 표시용 값이
 * 아니라 **동시성 제어의 근거**다 — `IssueTrackerLinkRepository.claim` 참고.
 */
@Table("issue_tracker_link")
data class IssueTrackerLinkEntity(
    @Id val id: Long? = null,
    @Column("issue_id") val issueId: Long,
    val provider: String,
    @Column("external_key") val externalKey: String? = null,
    @Column("external_url") val externalUrl: String? = null,
    @Column("sync_state") val syncState: String,
    @Column("sync_error") val syncError: String? = null,
    @Column("synced_at") val syncedAt: Instant? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null
)
