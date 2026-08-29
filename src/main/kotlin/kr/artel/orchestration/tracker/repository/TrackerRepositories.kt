package kr.artel.orchestration.tracker.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.tracker.entity.IssueTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.ProjectTrackerLinkEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface ProjectTrackerLinkRepository : CoroutineCrudRepository<ProjectTrackerLinkEntity, Long> {

    suspend fun findByProjectIdAndProvider(projectId: Long, provider: String): ProjectTrackerLinkEntity?

    @Modifying
    @Query("DELETE FROM project_tracker_link WHERE project_id = :projectId AND provider = :provider")
    suspend fun deleteByProjectIdAndProvider(projectId: Long, provider: String): Int

    /**
     * `installation` callback 이 부르는 upsert. 저장소 선택보다 `installation` 이 먼저 오므로, 행이
     * 없으면 저장소 없이 만들고 있으면 `installation_ref` 만 갈아 끼운다 — 이미 고른 저장소를 재설치가
     * 지우면 안 된다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO project_tracker_link (project_id, provider, installation_ref, connected_by, created_at, updated_at)
        VALUES (:projectId, :provider, :installationRef, :connectedBy, :now, :now)
        ON CONFLICT (project_id, provider) DO UPDATE
        SET installation_ref = EXCLUDED.installation_ref,
            connected_by = EXCLUDED.connected_by,
            updated_at = EXCLUDED.updated_at
        """
    )
    suspend fun attachInstallation(
        projectId: Long,
        provider: String,
        installationRef: String,
        connectedBy: Long,
        now: Instant
    ): Int

    /** `link` 설정(저장소와 자동 `sync` 기준). `installation_ref` 는 설치 흐름의 것이라 건드리지 않는다. */
    @Modifying
    @Query(
        """
        INSERT INTO project_tracker_link (
            project_id, provider, external_workspace, external_repository,
            auto_sync_severities, connected_by, created_at, updated_at
        )
        VALUES (:projectId, :provider, :workspace, :repository, :autoSyncSeverities, :connectedBy, :now, :now)
        ON CONFLICT (project_id, provider) DO UPDATE
        SET external_workspace = EXCLUDED.external_workspace,
            external_repository = EXCLUDED.external_repository,
            auto_sync_severities = EXCLUDED.auto_sync_severities,
            connected_by = EXCLUDED.connected_by,
            updated_at = EXCLUDED.updated_at
        """
    )
    suspend fun upsertTarget(
        projectId: Long,
        provider: String,
        workspace: String,
        repository: String,
        autoSyncSeverities: String,
        connectedBy: Long,
        now: Instant
    ): Int
}

interface IssueTrackerLinkRepository : CoroutineCrudRepository<IssueTrackerLinkEntity, Long> {

    suspend fun findByIssueIdAndProvider(issueId: Long, provider: String): IssueTrackerLinkEntity?

    /** 목록 한 페이지의 `tracker` 필드를 한 번에 채우는 배치 읽기. 줄마다 조회하면 N+1 이 된다. */
    fun findByIssueIdIn(issueIds: Collection<Long>): Flow<IssueTrackerLinkEntity>

    /**
     * 내보낼 권한을 **`claim`** 한다. 이 문장 하나가 lock 을 대신한다.
     *
     * 행이 없으면 INSERT 가 `claim` 이다. 있으면 `FAILED`(사람이 다시 시도) 또는 유예를 넘겨 굳은
     * `PENDING`(내보내는 도중 프로세스가 죽은 흔적)일 때만 다시 `claim` 한다. `SYNCED` 는 절대 다시
     * `claim` 되지 않는다.
     *
     * ⚠️ **이것이 막는 것과 막지 못하는 것.** 동시 요청, 사람이 누르는 재시도, agent 프레임 재전송에서
     * 외부 이슈가 둘 생기는 일은 없다. 막지 못하는 경우는 하나다 — 외부 호출이 성공하고 `markSynced`
     * 가 실행되기 **전에** 프로세스가 죽으면 행이 `PENDING` 으로 남고, 유예가 지난 뒤의 재시도가 두
     * 번째 이슈를 만든다. 그 창을 닫으려면 재`claim` 마다 저장소를 검색해 marker 를 찾아야 하는데,
     * 창의 폭(HTTP 201 수신과 UPDATE 한 문장 사이)에 비해 GitHub search 의 rate limit 과 지연 일관성을
     * 새 실패 축으로 들이는 값이 맞지 않는다. 열어 두되 숨기지 않는다 — 재`claim` 은 호출부가 warn
     * 로그로 남긴다.
     *
     * 동시 요청 둘 중 하나는 INSERT 하고 다른 하나는 충돌한다. 충돌한 쪽의 `WHERE` 는 값이 방금
     * 만들어진 `PENDING` 이라 거짓이 되어 아무 행도 돌려주지 않는다 — 호출부는 그것을 "다른 요청이
     * 이미 가져갔다"로 읽고 조용히 끝낸다.
     *
     * lock 을 잡지 않는 이유는 외부 HTTP 호출을 transaction 안으로 끌고 들어가지 않기 위해서다.
     * R2DBC connection pool 이 작아(테스트 max 3) 느린 GitHub 응답 하나가 pool 전체를 붙잡으면 안 된다.
     *
     * @return `claim` 한 행의 id. `claim` 하지 못했으면 null.
     */
    @Query(
        """
        INSERT INTO issue_tracker_link (issue_id, provider, sync_state, sync_error, created_at, updated_at)
        VALUES (:issueId, :provider, 'PENDING', NULL, :now, :now)
        ON CONFLICT (issue_id, provider) DO UPDATE
        SET sync_state = 'PENDING', sync_error = NULL, updated_at = :now
        WHERE issue_tracker_link.sync_state = 'FAILED'
           OR (issue_tracker_link.sync_state = 'PENDING' AND issue_tracker_link.updated_at < :staleBefore)
        RETURNING id
        """
    )
    suspend fun claim(issueId: Long, provider: String, now: Instant, staleBefore: Instant): Long?

    @Modifying
    @Query(
        """
        UPDATE issue_tracker_link
        SET sync_state = 'SYNCED', external_key = :externalKey, external_url = :externalUrl,
            sync_error = NULL, synced_at = :now, updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun markSynced(id: Long, externalKey: String, externalUrl: String, now: Instant): Int

    /**
     * 실패를 기록한다. [reason] 은 **우리가 쓴 요약**이다 — 이 값은 화면으로 나가므로 외부 응답의
     * 원문을 담지 않는다(`error-handling.md` 의 4xx 규약과 같은 이유).
     */
    @Modifying
    @Query(
        """
        UPDATE issue_tracker_link
        SET sync_state = 'FAILED', sync_error = :reason, updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: Long, reason: String, now: Instant): Int
}
