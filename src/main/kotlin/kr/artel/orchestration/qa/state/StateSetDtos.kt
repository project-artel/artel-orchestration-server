package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** 값 하나의 관측 상태. 계약 문서의 `값과 관측 상태` 절. */
enum class ObservationStatus {
    /** 값이 관측됐다. 게임이 읽은 `null` 도 여기다. */
    KNOWN,

    /** 관측을 시도했지만 읽지 못했다. `reason` 에 SDK 가 댄 이유가 있다. */
    UNREAD,

    /** 아직 관측하지 않았거나 관측 범위 밖이다. 상태에 없는 값을 물으면 이것으로 답한다. */
    UNKNOWN,

    /** 이전 값은 있으나 지금도 그런지 보장할 수 없다. 값은 진단용이다. */
    STALE,
}

enum class SyncState {
    /** 이 stream 의 whole `pulse` 를 아직 받지 못했다. 이때의 상태로 행동하지 않는다. */
    AWAITING_WHOLE,
    SYNCED,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Observation(
    val status: ObservationStatus,
    /** 관측된 값. 컬렉션은 개수가 온다(`count` 와 같은 수). 게임이 읽은 `null` 은 JSON `null` 이다. */
    val value: JsonNode? = null,
    val count: Int? = null,
    /** [ObservationStatus.UNREAD] 의 이유. */
    val reason: String? = null,
    val observedFrame: Long? = null,
    /** 이 값을 실어 온 `pulse` 의 `reading` 번호. */
    val sourceReading: Long? = null,
    /** 서버 시계. SDK 시계와 맞춰져 있다고 가정하지 않는다. */
    val receivedAt: Instant? = null,
)

/** 마지막 `pulse` 가 말한 관측 범위. `gaps` 가 비어 있지 않으면 그 범위의 누락은 부재가 아니다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Coverage(
    val watching: Int? = null,
    val unresolved: Int? = null,
    val unwatchable: Int? = null,
    val gaps: List<String> = emptyList(),
)

/**
 * 무엇을 볼지. Planner 가 내는 관측 범위의 v0.1 이다. 칸이 `null` 이면 그 축으로 거르지 않는다.
 *
 * 실행 가능한 코드는 받지 않는다. 이름과 접두어로만 고른다.
 */
data class ObservationSpec(
    /** `runtime`, `persistent`, [ACTIVE_SCENE], [ALL_SCENES], 또는 정확한 scope(`scene:Battle#2`). */
    val scopes: List<String>? = null,
    /** 객체 path 접두어. */
    val paths: List<String>? = null,
    /** 객체 멤버 이름(`Type::member`). 안 고른 멤버는 빠지고, 어디에도 없으면 `unobserved` 에 든다. */
    val members: List<String>? = null,
    /** static 이름(`Type::member`). */
    val statics: List<String>? = null,
    val includeInactive: Boolean = true,
    val includeStale: Boolean = true,
    val maxEntities: Int = DEFAULT_MAX_ENTITIES,
) {
    companion object {
        const val ACTIVE_SCENE = "scene"
        const val ALL_SCENES = "scene:*"
        const val DEFAULT_MAX_ENTITIES = 400
    }
}

/** 한 `stateVersion` 의 상태. 담긴 값들의 관측 frame 은 서로 다를 수 있다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StateSnapshot(
    val gameInstanceId: Long,
    val stateVersion: Long,
    val streamId: String?,
    /** SDK 가 보낸 프로세스 세대(ARTEL-1300). 옛 SDK 면 null. */
    val runtime: String? = null,
    val sync: SyncState,
    val reading: Long?,
    val frame: Long?,
    val scene: String?,
    val sceneScope: String?,
    val coverage: Coverage?,
    val entities: List<EntityView>,
    val statics: List<StaticView>,
    /** 물었지만 고른 범위 어디에서도 관측된 적 없는 이름. 값이 없다는 뜻이 아니라 모른다는 뜻이다. */
    val unobserved: List<String>,
    /** `maxEntities` 때문에 빠진 객체 수. */
    val omittedEntities: Int,
    /** whole 을 기다리는 동안 적용하지 않은 델타 수. */
    val ignoredDeltas: Long,
    /** `seq` 의 빈자리를 본 횟수. 볼 때마다 whole 을 다시 받았다. */
    val seqGaps: Int = 0,
    /** 이미 받은 `seq` 라 버린 수. */
    val duplicates: Int = 0,
    /** `runtime` 이 바뀌어 상태를 버린 횟수. */
    val runtimeChanges: Int = 0,
) {
    companion object {
        fun empty(gameInstanceId: Long) = StateSnapshot(
            gameInstanceId = gameInstanceId, stateVersion = 0, streamId = null, sync = SyncState.AWAITING_WHOLE,
            reading = null, frame = null, scene = null, sceneScope = null, coverage = null,
            entities = emptyList(), statics = emptyList(), unobserved = emptyList(), omittedEntities = 0, ignoredDeltas = 0,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EntityView(
    val entityId: String,
    val sdkId: Long?,
    val scope: String,
    val path: String?,
    val selector: String?,
    val tag: String?,
    val active: Boolean,
    /** 객체 자체의 관측 상태. 마지막 whole 에 다시 안 실렸으면 [ObservationStatus.STALE] 다. */
    val status: ObservationStatus,
    val observedReading: Long?,
    val world: JsonNode?,
    val rect: JsonNode?,
    /** `text`·`offers`·`ui`·`engine`·`uitk`. 실려 올 때마다 통째로 바뀐다. */
    val fragments: Map<String, Observation>,
    /** `Type::member#순번` 에서 값으로. */
    val members: Map<String, Observation>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class StaticView(
    /** `Type::member`, holder 가 여럿이면 `@순번` 이 붙는다. */
    val memberId: String,
    val holderPath: String?,
    val observation: Observation,
)
