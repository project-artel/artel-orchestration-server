package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
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

    /** 같은 컴포넌트의 같은 필드 안에서 몇 번째인가. 목록 필드의 색인(`sprites[4]`)과 맞춘다. */
    @Column("position")
    val position: Int? = null,

    /** 이 컴포넌트가 붙은 오브젝트의 하이어라키 경로. 같은 타입이 여럿 놓이면 목록이 주인마다 다르다. */
    @Column("owner_path")
    val ownerPath: String? = null,

    /** 가리키는 것의 타입. `pulse` 가 그 값을 주는지 가른다 — 그림은 주고 GameObject 의 그림은 안 준다. */
    @Column("target_type")
    val targetType: String? = null,

    /** 가리키는 프리팹에 실린 컴포넌트 타입들. */
    @Column("carries")
    val carries: Json = Json.of("[]"),

    @Column("created_at")
    val createdAt: Instant? = null,
)
