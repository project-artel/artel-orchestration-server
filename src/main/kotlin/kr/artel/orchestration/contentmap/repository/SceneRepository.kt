package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.SceneCollectionFamilyEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 씬 조회. 갱신 단위가 씬이므로 (content_map, name) 조회가 적재의 멱등 키다.
 */
interface SceneRepository : CoroutineCrudRepository<SceneEntity, Long> {

    suspend fun findByContentMapIdAndName(contentMapId: Long, name: String): SceneEntity?

    fun findByContentMapIdOrderByNameAsc(contentMapId: Long): Flow<SceneEntity>
}

/**
 * 이 씬에서 collection 으로 판정된 경로 family (ARTEL-649).
 *
 * `screen` 옆이 아니라 `scene` 옆에 사는 이유: collection 인지 아닌지는 **씬의 구조적 사실**이고,
 * 그 씬의 어느 화면에서 보든 같은 답이어야 한다. 화면마다 따로 들면 같은 family 가 화면에 따라
 * 다르게 판정되어 `discriminator` 규칙이 화면마다 갈린다.
 */
interface SceneCollectionFamilyRepository : CoroutineCrudRepository<SceneCollectionFamilyEntity, Long> {

    @Query("SELECT family FROM scene_collection_family WHERE scene_id = :sceneId")
    fun findFamiliesBySceneId(sceneId: Long): Flow<String>

    /**
     * 이 family 를 collection 으로 기억한다. 이미 있으면 아무 일도 하지 않는다.
     *
     * 멱등을 코드가 아니라 `uk_scene_collection_family` 가 강제한다. 같은 빌드를 두 서버가 관측하면
     * 각자 자기 `fold` 를 보고 같은 family 를 동시에 처음 보므로, "이미 아나" 를 코드가 판정하면
     * 그 검사는 경합에 진다.
     *
     * `first_observed_at` 은 `DO NOTHING` 이라 처음 값이 남는다. 언제부터 이 family 를 빼기
     * 시작했는지가 이 칸의 뜻이고, 재관측이 덮으면 그 뜻이 사라진다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO scene_collection_family (scene_id, family)
        VALUES (:sceneId, :family)
        ON CONFLICT (scene_id, family) DO NOTHING
        """
    )
    suspend fun remember(sceneId: Long, family: String): Long
}
