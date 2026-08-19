package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.ScreenTransitionEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 화면. QA 런 전에는 0행이고 그게 정상이다 — 정적 분석은 화면을 모른다.
 */
interface ScreenRepository : CoroutineCrudRepository<ScreenEntity, Long> {

    fun findBySceneIdOrderByIdAsc(sceneId: Long): Flow<ScreenEntity>
}

/** 화면 전이. 관측으로만 생긴다. */
interface ScreenTransitionRepository : CoroutineCrudRepository<ScreenTransitionEntity, Long> {

    fun findByFromScreenIdOrderByIdAsc(fromScreenId: Long): Flow<ScreenTransitionEntity>
}

/**
 * 씬 전이. 정적 후보로 출발해 QA 런이 검증한다.
 *
 * [findByFromSceneIdAndVerifiedAtIsNullOrderByIdAsc] 가 커버리지 구멍의 목록이고, QA agent 에게 다음에 무엇을 시도할지 알려주는
 * 유일한 신호다.
 */
interface SceneEdgeRepository : CoroutineCrudRepository<SceneEdgeEntity, Long> {

    fun findByFromSceneIdOrderByIdAsc(fromSceneId: Long): Flow<SceneEdgeEntity>

    fun findByFromSceneIdAndVerifiedAtIsNullOrderByIdAsc(fromSceneId: Long): Flow<SceneEdgeEntity>

    fun findByFromSceneIdAndToSceneName(fromSceneId: Long, toSceneName: String): Flow<SceneEdgeEntity>
}
