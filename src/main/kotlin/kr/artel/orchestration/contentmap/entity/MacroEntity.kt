package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * macro — 이름과 parameter 를 붙인 action 시퀀스. **등록된 것만 담는다.**
 *
 * `capability` 행은 버튼 하나가 하는 일 하나를 given · when · then 세 칸으로 담아 시퀀스를 담을
 * 칸이 없다. 이 표가 그 자리다(ARTEL-919).
 *
 * 등록하지 않은 초안은 런의 상태에 살고 런이 끝나면 사라진다(ARTEL-925). 초안에 남길 값이
 * 없었다는 뜻이므로 이 표에 오지 않는다.
 *
 * ## `content_map` 에 매달린다
 *
 * build 당 하나인 `content_map`(V63)에 매달아, 게임 build 가 바뀌면 macro 도 같이 무효가 되게
 * 한다. `scene` 에 매달지 않는 이유는 macro 하나가 `scene` 경계를 넘을 수 있어서고, `screen` 에
 * 매달지 않는 이유는 `screen` 이 런타임 관측으로 굳는 행이라(ARTEL-654) 아직 안 굳었거나 나중에
 * 다시 갈라질 때 macro 가 같이 흔들리기 때문이다. 관계만 [ScreenMacroEntity] 로 둔다.
 *
 * ## `precondition` 칸이 없다
 *
 * 첫 `require()` statement 가 그 자리를 대신한다.
 */
@Table("macro")
data class MacroEntity(
    @Id
    val id: Long? = null,

    @Column("content_map_id")
    val contentMapId: Long,

    /** 진입점 `def` 의 이름. 유일 범위는 같은 [contentMapId] 안이다(`uk_macro_name`). */
    @Column("name")
    val name: String,

    /**
     * **원본.** `ast.parse` 가 주석과 공백을 버리므로 [definitionJson] 에서 다시 찍어내면 agent 가
     * 적은 그대로가 아니고, 정의가 바뀌었을 때 사람이 보는 diff 가 실제로 바뀐 줄을 가리키지
     * 않는다. 사람이 읽고 diff 하는 몫이 이 칸이다.
     */
    @Column("source")
    val source: String,

    /**
     * **파생.** 실행할 때마다 다시 파싱하지 않기 위한 실행용 캐시다. 등록이 한 번 만들고 실행은
     * 이것만 본다. **질의 대상이 아니다.**
     *
     * helper `def` 를 각각 담고 호출을 호출로 담는다. 한 [source] 안의 helper 호출을 펼친 결과가
     * 아니다. tree 의 정확한 모양은 ARTEL-918 이 확정한다.
     *
     * `require` statement 마다 조건과 `remedy` 문자열이 한 쌍으로 선다.
     * `ck_macro_require_carries_remedy` 가 `remedy` 없는 `require` 를 막지만, 그 제약에 걸린
     * 예외는 제약 이름만 실려 agent 가 무엇을 고쳐야 하는지 읽을 수 없다 — 그래서
     * `MacroDefinitionService` 가 Kotlin 에서 먼저 거르고 제약은 backstop 으로 둔다.
     */
    @Column("definition_json")
    val definitionJson: Json,

    /**
     * 진입점 `def` 줄에서 읽은 parameter 이름. **순서가 뜻을 가진다** — `run_macro` 가 실행 시점에
     * 인자를 위치로 대응시킨다.
     *
     * [definitionJson] 안에도 같은 이름이 있으므로 일부러 둔 사본이다. 위치로 대응시키는 쪽이
     * tree 를 풀지 않고 개수와 순서를 보게 하는 것이 목적이고, 원본은 tree 쪽이다. 선언 타입은
     * 여기 두지 않는다 — 타입 검사는 tree 를 이미 읽은 자리에서 한다.
     */
    @Column("parameter_names")
    val parameterNames: Json = Json.of("[]"),

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)

/**
 * screen_macro — 지금 이 화면에서 쓸 수 있는 macro. `screen_capability`(V40)가 선례다.
 *
 * 한 행은 (macro, `screen`) 한 쌍이고 같은 쌍은 한 번만 선다. 지금은 첫
 * `require(scene() == ...)` 가 하던 일을 이 구조가 대신한다.
 *
 * ## 다대다인 이유
 *
 * 두 방향이 모두 있다. 대화창을 닫는 macro 처럼 여러 `screen` 에서 통하는 것이 있고, 반대로 한
 * macro 가 `screen` 경계를 넘기도 한다.
 *
 * ## 빈 관계는 뜻을 가진다
 *
 * 어느 `screen` 과도 안 이어진 macro 를 허용한다. 그 상태의 뜻은 **아직 어디서 쓸지 모른다**는
 * 것이고, 아무 데서나 쓸 수 있다는 뜻이 아니다. 이 표를 읽는 쪽이 빈 관계를 "아무 데서나 된다" 로
 * 읽으면 안 된다.
 *
 * 복합 PK 라 `@Id` 가 없다 — R2DBC 는 복합키 엔티티의 자동 저장을 지원하지 않으므로 적재는
 * 명시 INSERT 로 한다([ScreenCapabilityEntity] 와 같은 사정이다).
 */
@Table("screen_macro")
data class ScreenMacroEntity(
    @Column("screen_id")
    val screenId: Long,

    @Column("macro_id")
    val macroId: Long,
)
