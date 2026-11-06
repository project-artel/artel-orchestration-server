package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * content_map — **게임 빌드 하나에 지도 하나.** 그 빌드에 대해 지금까지 알아낸 것 전체다.
 *
 * 스캔 한 번의 결과가 아니라 **축적**이다. SDK 는 씬을 재방문할 때마다 그 씬만 덮어쓰므로 갱신
 * 단위는 씬이고 이 행은 계속 살아 있다.
 *
 * `capture` 가 키에서 빠지고 [SceneEntity.capture] 로 내려갔다(ARTEL-642). 근거가 먼저 오든 QA
 * 런이 먼저 돌든 같은 행에 쌓이고, 같은 씬을 다시 읽으면 마지막 walk 가 이긴다.
 *
 * [schemaVersion] · [capture] · [evidenceDigest] 가 nullable 인 것은 **근거 문서 없이도 이 행이
 * 서기 때문**이다. 셋 다 문서가 말해 주는 것이라, 문서를 아직 못 받은 지도에서는 아무도 말한 적이
 * 없다. 그때 더미값을 넣으면 진짜 헤더와 같은 칸에 앉는다.
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

    /** 근거 문서의 `schema`. 세대. 뜻이 바뀌면 올라간다. 문서가 없으면 null. */
    @Column("schema_version")
    val schemaVersion: Int? = null,

    /**
     * 이 지도에 마지막으로 등록된 근거 문서가 신고한 [Capture]. 문서가 없으면 null.
     *
     * 판정에 쓰지 않는다 — 값을 어느 상태에서 읽었는지는 씬마다 다를 수 있어
     * [SceneEntity.capture] 가 답한다. 이 칸은 요약 패널이 "이 빌드의 근거를 어디서 떴나" 를
     * 한 줄로 보여 주기 위한 것이다.
     */
    @Column("capture")
    val capture: String? = null,

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
     * 구워진 근거 전체의 지문(`build.evidence`). 값이 달라지면 **코드가 바뀐 것**이고, 그때
     * evidence 출신 기능의 검증을 되돌린다. 문서가 없으면 null.
     */
    @Column("evidence_digest")
    val evidenceDigest: String? = null,

    /**
     * 이 행을 세운 경로([ContentMapRoot]). 근거 등록이 세웠으면 `evidence`, 관측이 세웠으면
     * `observation`.
     *
     * 관측이 세운 지도에 나중에 근거 문서가 들어오면 `evidence` 로 **올라간다.** 내려가지는
     * 않는다 — 한 번 근거를 가진 지도는 계속 근거를 가진 지도다.
     */
    @Column("rooted_by")
    val rootedBy: String = ContentMapRoot.EVIDENCE.wire,

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
