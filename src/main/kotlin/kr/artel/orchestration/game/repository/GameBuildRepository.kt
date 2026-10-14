package kr.artel.orchestration.game.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.game.entity.GameBuildEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 빌드에는 soft delete가 없다. 지워도 SDK가 같은 버전을 다시 보고하는 순간 되살아나므로,
 * 지울 수 있다는 인상만 주고 실제로는 지워지지 않는 동작이 된다.
 *
 * 조회는 프로젝트와 마찬가지로 참여자 조인과 프로젝트의 `deleted_at IS NULL`로 좁힌다.
 */
interface GameBuildRepository : CoroutineCrudRepository<GameBuildEntity, Long> {

    /**
     * 화면의 빌드 목록. non-editor 행은 최근 것부터, editor 행은 마지막이다(ARTEL-1014).
     *
     * `ScenarioPathService` 와 `ScenarioCaseFactService` 의 `contentMapIdOf` 가 이 순서의 첫 행을 "지금 build" 로
     * 쓴다. 그래서 editor 행은 non-editor 행이 하나도 없을 때만 첫 행이 된다. 순서를 바꾸면 두 곳이 읽는 content
     * map 도 바뀐다.
     */
    @Query(
        """
        SELECT gb.* FROM game_build gb
        JOIN project p ON p.id = gb.project_id
        JOIN project_member m ON m.project_id = gb.project_id
        WHERE gb.project_id = :projectId AND m.app_user_id = :userId
          AND p.deleted_at IS NULL
        ORDER BY gb.is_editor, gb.created_at DESC, gb.id DESC
        """
    )
    fun findAllForMember(projectId: Long, userId: Long): Flow<GameBuildEntity>

    @Query(
        """
        SELECT gb.* FROM game_build gb
        JOIN project p ON p.id = gb.project_id
        JOIN project_member m ON m.project_id = gb.project_id
        WHERE gb.id = :id AND gb.project_id = :projectId AND m.app_user_id = :userId
          AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleById(id: Long, projectId: Long, userId: Long): GameBuildEntity?

    /**
     * SDK 등록이 development build 를 찾는다. editor 행은 version 이 같아도 맞지 않는다(ARTEL-1014) — editor 는
     * development build 와 같은 Player Settings 버전을 보고하므로, 걸리면 두 실행이 다시 한 행이 된다.
     */
    @Query("SELECT * FROM game_build WHERE project_id = :projectId AND version = :version AND is_editor = FALSE")
    suspend fun findNonEditorByVersion(projectId: Long, version: String): GameBuildEntity?

    /** 프로젝트의 editor 행. 프로젝트마다 많아야 하나다(`uk_game_build_project_editor`, ARTEL-1014). */
    @Query("SELECT * FROM game_build WHERE project_id = :projectId AND is_editor = TRUE")
    suspend fun findEditor(projectId: Long): GameBuildEntity?

    /**
     * 프로젝트의 게임 버전, 최근 것부터(ARTEL-994). editor 행은 게임 버전이 아니라서 빠진다(ARTEL-1014).
     * TR 을 만들 때 첫 행을 그 TR 의 버전으로 적고, 가져오기 트리가 이 순서로 버전을 묶는다. 접근 확인은
     * 호출하는 쪽이 한다.
     */
    @Query(
        """
        SELECT * FROM game_build
        WHERE project_id = :projectId AND is_editor = FALSE
        ORDER BY created_at DESC, id DESC
        """
    )
    fun findNonEditorNewestFirst(projectId: Long): Flow<GameBuildEntity>

    /**
     * 빌드 id 만으로 접근을 확인한다. SDK 는 등록 응답으로 받은 빌드 id 를 들고 오지만 프로젝트
     * id 는 다시 보내지 않으므로, [findAccessibleById] 와 달리 프로젝트를 인자로 받지 않는다.
     *
     * 부재와 권한 없음을 구분하지 않고 null 하나로 답한다 — 구분해서 알려주면 id 를 훑어 남의
     * 빌드가 존재한다는 사실을 알아낼 수 있다.
     */
    @Query(
        """
        SELECT gb.* FROM game_build gb
        JOIN project p ON p.id = gb.project_id
        JOIN project_member m ON m.project_id = gb.project_id
        WHERE gb.id = :id AND m.app_user_id = :userId AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleByIdForMember(id: Long, userId: Long): GameBuildEntity?
}
