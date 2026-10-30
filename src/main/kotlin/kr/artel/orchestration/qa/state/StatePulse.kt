package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * `STATE_SET` 이 읽는 `pulse` 문서(schema 3). 상태를 합치는 데 쓰는 칸만 판다.
 *
 * [kr.artel.orchestration.contentmap.observe.PulseReading] 과 따로 두는 이유는 읽는 목적이 달라서다. 그쪽은
 * 화면을 가르려고 값을 일부러 버리고, 이쪽은 값과 그 관측 상태가 전부다.
 *
 * 값 칸(`value`, `text`)은 [JsonNode] 다. 게임이 읽은 값의 모양은 열려 있고, 무엇보다 **칸이 없는 것과
 * `null` 이 실려 온 것이 다른 말**이다. Jackson 은 `JsonNode` 칸에 실린 `null` 을 `NullNode` 로 주고, 칸이
 * 없으면 Kotlin 기본값 `null` 을 남긴다.
 *
 * 모르는 칸은 흘린다. SDK 는 칸을 더하면서 배포된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StatePulse(
    /** 게임 프로세스의 세대(ARTEL-1299). 바뀌면 SDK 의 객체 id 가 같은 객체를 뜻하지 않는다. 옛 SDK 는 안 보낸다. */
    val runtime: String? = null,
    /** `start_readings` 한 번의 세대. 있으면 연결 대신 이것이 stream 을 가른다. */
    val stream: String? = null,
    /** 그 stream 에서 보내려고 쥔 `pulse` 의 번호. 1부터 빈자리 없이 오른다 — 빈자리는 곧 누락이다. */
    val seq: Long? = null,
    /** 로드된 씬마다 이름과 Unity `Scene.handle`. 같은 이름도 다시 로드하면 handle 이 바뀐다. */
    val scenes: List<StatePulseScene>? = null,
    val reading: Long? = null,
    val frame: Long? = null,
    /** 활성 씬. 델타에도 실린다. 객체가 다른 씬에 살면 객체가 제 씬을 댄다. */
    val scene: String? = null,
    val whole: Boolean = false,
    val statics: List<StatePulseStatic> = emptyList(),
    val active: List<StatePulseObject> = emptyList(),
    val deactive: List<StatePulseObject> = emptyList(),
    /** 파괴된 객체. `씬/selector` 꼴이다. 통에 안 실린 것은 파괴가 아니라 이 목록에 든 것만 파괴다. */
    val gone: List<String> = emptyList(),
    val watching: Int? = null,
    val unresolved: Int? = null,
    val unwatchable: Int? = null,
    /** `pulse` 가 잘린 자리. 칸이 없으면 이번 `pulse` 가 이 말을 하지 않은 것이고, 빈 목록이면 잘린 데가 없다. */
    val gaps: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatePulseScene(
    val name: String? = null,
    val handle: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatePulseObject(
    /** SDK 가 매긴 런타임 instance id. 프로세스를 넘지 못한다. */
    val id: Long? = null,
    val scene: String? = null,
    val path: String? = null,
    val selector: String? = null,
    val tag: String? = null,
    /** 바뀌었을 때만 실린다. */
    val text: JsonNode? = null,
    val world: JsonNode? = null,
    val rect: JsonNode? = null,
    val offers: JsonNode? = null,
    val ui: JsonNode? = null,
    val engine: JsonNode? = null,
    val uitk: JsonNode? = null,
    val members: List<StatePulseMember> = emptyList(),
    val by: List<StatePulseComponent> = emptyList(),
) {
    /** `by` 로 묶여 온 멤버를 `on` 을 채워 편다. */
    fun spreadMembers(): List<StatePulseMember> =
        members + by.flatMap { component -> component.m.map { it.copy(on = it.on ?: component.on) } }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatePulseComponent(
    val on: String? = null,
    val m: List<StatePulseMember> = emptyList(),
)

/** 멤버 하나. `value`·`count`·`unread` 가운데 하나가 온다. 셋 다 없으면 게임이 `null` 을 읽은 것이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StatePulseMember(
    val on: String? = null,
    val member: String? = null,
    /** 한 객체에 같은 타입 컴포넌트가 여럿일 때의 순번. */
    val among: Int? = null,
    val value: JsonNode? = null,
    val count: Int? = null,
    val unread: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatePulseStatic(
    val declaring: String? = null,
    val member: String? = null,
    val type: String? = null,
    val value: JsonNode? = null,
    val count: Int? = null,
    val unread: String? = null,
    /** holder 가 여럿인 멤버의 holder 순번. */
    val instance: Int? = null,
    /** 값을 쥔 holder 의 scene path. 있으면 실제 static 이 아니라 객체가 쥔 값이다. */
    val path: String? = null,
)
