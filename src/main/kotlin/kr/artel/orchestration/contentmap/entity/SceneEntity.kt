package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * scene — 정적. evidence 순회가 만난 씬.
 *
 * 갱신 단위가 이것이다. 근거 문서는 씬 키로 관리되고 재방문하면 그 씬만 덮어쓴다.
 */
@Table("scene")
data class SceneEntity(
    @Id
    val id: Long? = null,

    @Column("content_map_id")
    val contentMapId: Long,

    @Column("name")
    val name: String,

    /**
     * 이 씬의 값을 **어느 상태에서 읽었나**([Capture]). 근거 walk 를 지나지 않은 씬은 null.
     *
     * `content_map` 에 있던 것이 여기로 내려왔다(ARTEL-642). 갱신 단위가 원래 씬이라 이 자리가
     * 맞고, 같은 씬을 다른 상태에서 다시 읽으면 마지막 walk 의 값이 이긴다.
     *
     * `editor` 는 저장된 값이고 `player` 는 플레이가 지나간 뒤의 값이라 **같은 필드가 다른
     * 뜻이다** — 적의 `label` 이 authored `20` 인가 남은 체력 `20` 인가가 갈린다.
     */
    @Column("capture")
    val capture: String? = null,

    /** 이 씬을 어디서 알아냈나([SceneOrigin]). */
    @Column("origin")
    val origin: String = SceneOrigin.EVIDENCE.wire,

    /**
     * 식별자를 남긴 설명. 무엇이 무엇을 판정하고 어디로 이어지는지.
     *
     * 순수 자연어로 옮기지 않는다 — 그러면 조인 키가 문장 밖으로 사라져 TC 가 그 문장을 읽고도
     * 무엇을 눌러야 할지 모른다.
     */
    @Column("summary")
    val summary: String? = null,

    /**
     * 근거의 `scenes[]` 에 이름만 있고 순회하지 못한 씬은 `false`. 런타임은 로드된 씬만 볼 수 있다.
     * `false` 면 이 씬의 기능이 비어 있는 것이 **정상**이다.
     */
    @Column("walked")
    val walked: Boolean = false,

    /**
     * **게임을 켜면 열리는 씬인가**(ARTEL-659). 지도 하나에 하나뿐이다.
     *
     * 씬 그래프는 순환이라 입구를 구조로는 알 수 없다 — 모든 씬이 서로 닿는다. 그래서 저작이
     * 흐름을 계산할 때 아무 자리에서나 출발했고, 실측(런 233)에서 `진행도 == 5, 위치 == 0` 에서
     * 시작하라는 시나리오가 나왔다. 아무도 그렇게 게임을 시작하지 않는다.
     *
     * 어떻게 아는지는 적재기만 안다(유니티 빌드 인덱스 0). 여기서는 **입구라는 사실**만 든다 —
     * 다른 엔진이 붙어도 이 칸의 뜻은 그대로다.
     */
    val isEntry: Boolean = false,

    /**
     * 근거 walk 가 이 씬에서 찍은 대표 이미지. 화면이 여럿인 씬에서는 [ScreenEntity] 쪽이 더 정확하다.
     *
     * 값의 출처는 `content_map_scene_capture` 이고 여기 있는 것은 화면이 읽기 좋게 내린 사본이다.
     * 조회가 씬 한 줄만 읽고도 이미지를 낼 수 있어야 씬 수백 개짜리 지도에서 조인이 늘지 않는다.
     */
    @Column("image_object_key")
    val imageObjectKey: String? = null,

    @Column("image_width")
    val imageWidth: Int? = null,

    @Column("image_height")
    val imageHeight: Int? = null,

    @Column("image_captured_at")
    val imageCapturedAt: Instant? = null,

    /** 이미지를 못 만든 이유. [imageObjectKey] 와 동시에 차지 않는다 — 둘 중 하나만 산다. */
    @Column("image_failure_code")
    val imageFailureCode: String? = null,

    /** 이 씬에 대해 근거가 "못 봤다"고 말한 것. `dont-destroy-on-load-not-walked` 등. */
    @Column("gaps")
    val gaps: Json = Json.of("[]"),

    @Column("scanned_at")
    val scannedAt: Instant? = null,
)
