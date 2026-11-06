package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * `scene` 에 놓인 오브젝트 하나(ARTEL-1059). 왜 남기는지는 V114 에 있다.
 *
 * @property selector 문서의 `selector`. 없으면 `path` 다.
 * @property active 스캔 시점의 활성 상태.
 * @property components [SceneObjectComponent] 목록의 JSON. 문서의 컴포넌트 순서 그대로다.
 */
@Table("scene_object")
data class SceneObjectEntity(
    @Id
    val id: Long? = null,

    @Column("content_map_id")
    val contentMapId: Long,

    @Column("scene_id")
    val sceneId: Long,

    @Column("selector")
    val selector: String,

    @Column("active")
    val active: Boolean,

    @Column("components")
    val components: Json,

    @Column("created_at")
    val createdAt: Instant? = null,
)

/**
 * `scene_object.components` 의 원소.
 *
 * @property type 타입 이름의 **마지막 마디**. `scene_object_ref.owner_type` 과 같은 이유다 — 코드의
 *   표현식은 `SelectableObject.selectable` 처럼 짧은 이름으로 타입을 부른다.
 * @property fields 코드가 조건에서 읽거나 효과로 쓰는 이 타입의 필드, 이름 오름차순. 없으면 빈 목록이다.
 */
data class SceneObjectComponent(
    val type: String,
    val fields: List<String> = emptyList(),
)
