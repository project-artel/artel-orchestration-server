package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 컴포넌트의 직렬화 필드가 **무엇을 가리키나**(ARTEL-615).
 *
 * 기대결과가 코드 표현식을 그대로 부르면 QA 담당자가 게임에서 그 이름을 못 찾는다 —
 * `ChatWindowController.anyKeyPrompt` 는 코드의 말이고, 하이어라키에 있는 것은
 * `Canvas/ChatWindow/AnyKeyPrompt` 다. 문서가 그 대응을 이미 말한다.
 *
 * @property ownerType 타입 이름의 **마지막 마디**. 문서가 같은 타입을 네임스페이스까지 적기도 하고
 *   안 적기도 해서, 맞추는 쪽이 꼬리로 견딘다.
 * @property targetName 씬 오브젝트면 하이어라키 경로, 에셋이면 그 이름.
 */
@Table("scene_object_ref")
data class SceneObjectRefEntity(
    @Id
    val id: Long? = null,

    @Column("content_map_id")
    val contentMapId: Long,

    @Column("scene_id")
    val sceneId: Long,

    @Column("owner_type")
    val ownerType: String,

    @Column("field")
    val field: String,

    @Column("target_name")
    val targetName: String,

    @Column("created_at")
    val createdAt: Instant? = null,
)
