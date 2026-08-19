package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * content_map — (게임 빌드, capture) 단위로 지금까지 알아낸 것 전체.
 *
 * 스캔 한 번의 결과가 아니라 **축적**이다. SDK 는 씬을 재방문할 때마다 그 씬만 덮어쓰므로 갱신
 * 단위는 씬이고 이 행은 계속 살아 있다.
 *
 * [capture] 를 키에 넣는 이유: `editor` 는 저장된 값이고 `player` 는 플레이가 지나간 뒤의 값이라
 * **같은 필드가 다른 뜻이다.** 적의 `label` 이 authored `20` 인가 남은 체력 `20` 인가가 갈린다.
 *
 * 이름이 "명세"가 아니라 "지도"인 것은 이것이 콘텐츠의 지도이고, 명세는 이것을 읽고 쓰는
 * TC 이기 때문이다.
 */
@Table("content_map")
data class ContentMapEntity(
    @Id
    val id: Long? = null,

    @Column("game_build_id")
    val gameBuildId: Long,

    /** 근거 문서의 `schema`. 세대. 뜻이 바뀌면 올라간다. */
    @Column("schema_version")
    val schemaVersion: Int,

    /** [Capture] 중 하나. */
    @Column("capture")
    val capture: String,

    /**
     * 근거 문서의 `capabilities` — **문서가 하는 약속**이다.
     * `["build-info-v1", "selector-v1", "visual-roles-v1", "persistent-objects-v1"]`
     *
     * [schemaVersion] 이 세대라면 이쪽은 개별 약속이고 더하기만 한다. 필드 존재로 계약을 추론하면
     * 안 되기에 따로 있다 — `build` 는 `label` 의 뜻이 좁아지기 한 커밋 전에 들어왔고, 필드만 보고
     * 판단하면 그 쌍을 틀리게 읽는다.
     *
     * 적재기가 쓸 자리: `selector-v1` 이 없는 문서에서 `control_selector` 를 채우면 안 되고,
     * `visual-roles-v1` 이 없으면 `control_label` 을 컨트롤 이름으로 믿으면 안 된다.
     *
     * 원문 이름(`capabilities`)을 쓰지 않는 것은 [CapabilityEntity] 와 충돌하기 때문이다 — 한쪽은
     * 게임의 기능이고 한쪽은 문서의 계약인데 한 글자도 다르지 않다.
     */
    @Column("evidence_promises")
    val evidencePromises: Json = Json.of("[]"),

    /**
     * 구워진 근거 전체의 지문(`build.evidence`). 같은 [capture] 인데 값이 다르면 **코드가 바뀐
     * 것**이고, 그때 evidence 출신 기능의 검증을 되돌린다.
     */
    @Column("evidence_digest")
    val evidenceDigest: String,

    @Column("unity")
    val unity: String? = null,

    @Column("platform")
    val platform: String? = null,

    /** `mono` 또는 `il2cpp`. */
    @Column("backend")
    val backend: String? = null,

    @Column("development")
    val development: Boolean? = null,

    @Column("sdk_version")
    val sdkVersion: String? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
