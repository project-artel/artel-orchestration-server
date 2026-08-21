package kr.artel.orchestration.game.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.game.entity.GameInstanceEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 대시보드 조회는 project_member 조인과 두 개의 `deleted_at IS NULL`로 함께 좁힌다.
 * 조건을 서비스에서 걸러내지 않고 질의에 두어야, 조건을 빠뜨렸을 때 남의 인스턴스나 삭제된
 * 인스턴스가 조용히 새어나가지 않는다.
 *
 * 프로젝트의 삭제 여부까지 보는 이유는, 프로젝트를 지워도 game_instance 행은 그대로 남기
 * 때문이다. 그 조인이 빠지면 삭제된 프로젝트의 인스턴스가 살아남는다.
 */
interface GameInstanceRepository : CoroutineCrudRepository<GameInstanceEntity, Long> {

    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        JOIN project_member m ON m.project_id = gi.project_id
        WHERE gi.project_id = :projectId AND m.app_user_id = :userId
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        ORDER BY gi.created_at DESC, gi.id DESC
        """
    )
    fun findAllForMember(projectId: Long, userId: Long): Flow<GameInstanceEntity>

    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        JOIN project_member m ON m.project_id = gi.project_id
        WHERE gi.id = :id AND gi.project_id = :projectId AND m.app_user_id = :userId
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleById(id: Long, projectId: Long, userId: Long): GameInstanceEntity?

    /**
     * 프로젝트를 모른 채 인스턴스 하나에 대한 접근 권한을 확인한다.
     *
     * 화면 스트리밍 웹소켓은 URL에 instanceId만 싣는다. projectId까지 받으면 클라이언트가
     * 보낸 두 값이 서로 맞는지를 따로 확인해야 하고, 그 확인을 빠뜨리면 남의 프로젝트 id를
     * 붙여 보내는 것으로 조인을 통과시킬 수 있다. 인스턴스에서 프로젝트를 거슬러 올라가면
     * 그 경우가 아예 생기지 않는다.
     */
    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        JOIN project_member m ON m.project_id = gi.project_id
        WHERE gi.id = :id AND m.app_user_id = :userId
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleByIdForMember(id: Long, userId: Long): GameInstanceEntity?

    /**
     * SDK가 보고한 설치 식별자로 인스턴스를 찾는다.
     *
     * 프로젝트 접근 권한은 호출 전에 이미 확인된 상태여야 한다. sdkUuid는 자격증명이 아니라
     * 같은 프로젝트 안에서 설치본을 가르는 값일 뿐이라, 이 질의만으로는 아무것도 인가하지 않는다.
     */
    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        WHERE gi.project_id = :projectId AND gi.sdk_uuid = :sdkUuid
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        """
    )
    suspend fun findActiveBySdkUuid(projectId: Long, sdkUuid: String): GameInstanceEntity?

    @Query(
        """
        SELECT gi.* FROM game_instance gi
        WHERE gi.project_id = :projectId AND gi.sdk_uuid = :sdkUuid
          AND gi.deleted_at IS NOT NULL
        """
    )
    suspend fun findRetiredBySdkUuid(projectId: Long, sdkUuid: String): GameInstanceEntity?

    /**
     * 이 빌드를 마지막으로 보고한 인스턴스들. 원격 스캔이 "어디로 보낼까"를 여기서 고른다.
     *
     * 빌드에서 살아 있는 인스턴스로 가는 FK 경로가 없다 — WebSocket 세션은 `gameInstanceId` 로
     * 묶이고 근거 문서는 `gameBuildId` 로 묶인다. [GameInstanceEntity.lastGameBuildId] 가 바로 그
     * 틈을 메우려고 있는 칸이고(등록이 남긴다), 이 질의가 그것을 거슬러 올라간다.
     *
     * **붙어 있는지는 여기서 보지 않는다.** 그것은 DB 가 아니라 `SessionManager` 가 아는 사실이라,
     * 호출자가 이 목록을 받아 걸러 낸다. 순서를 최근에 붙은 것부터로 두는 이유: 같은 빌드를 두 대에서
     * 돌리는 것은 개발 중 흔하고, 그때 사람이 보고 있는 것은 방금 띄운 쪽이다. 한 번도 붙어 본 적
     * 없는 인스턴스(`last_connected_at IS NULL`)가 그것을 앞지르지 않게 `NULLS LAST` 로 둔다.
     */
    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        JOIN project_member m ON m.project_id = gi.project_id
        WHERE gi.last_game_build_id = :gameBuildId AND m.app_user_id = :userId
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        ORDER BY gi.last_connected_at DESC NULLS LAST, gi.id DESC
        """
    )
    fun findByLastGameBuildIdForMember(gameBuildId: Long, userId: Long): Flow<GameInstanceEntity>
}
