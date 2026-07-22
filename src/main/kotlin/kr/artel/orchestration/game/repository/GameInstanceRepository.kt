package kr.artel.orchestration.game.repository

import kr.artel.orchestration.game.entity.GameInstanceEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * 대시보드 조회는 project_member 조인과 두 개의 `deleted_at IS NULL`로 함께 좁힌다.
 * 조건을 서비스에서 걸러내지 않고 질의에 두어야, 조건을 빠뜨렸을 때 남의 인스턴스나 삭제된
 * 인스턴스가 조용히 새어나가지 않는다.
 *
 * 프로젝트의 삭제 여부까지 보는 이유는, 프로젝트를 지워도 game_instance 행은 그대로 남기
 * 때문이다. 그 조인이 빠지면 삭제된 프로젝트의 인스턴스가 살아남는다.
 */
interface GameInstanceRepository : ReactiveCrudRepository<GameInstanceEntity, Long> {

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
    fun findAllForMember(projectId: Long, userId: Long): Flux<GameInstanceEntity>

    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        JOIN project_member m ON m.project_id = gi.project_id
        WHERE gi.id = :id AND gi.project_id = :projectId AND m.app_user_id = :userId
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        """
    )
    fun findAccessibleById(id: Long, projectId: Long, userId: Long): Mono<GameInstanceEntity>

    /**
     * SDK가 제시한 키로 인스턴스를 찾는다. 호출자가 로그인한 사용자가 아니므로 참여자 조인은
     * 없지만, 삭제 조건은 그대로다. 지운 인스턴스나 지운 프로젝트의 키는 통하지 않는다.
     */
    @Query(
        """
        SELECT gi.* FROM game_instance gi
        JOIN project p ON p.id = gi.project_id
        WHERE gi.instance_key = :instanceKey
          AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
        """
    )
    fun findActiveByInstanceKey(instanceKey: String): Mono<GameInstanceEntity>
}
