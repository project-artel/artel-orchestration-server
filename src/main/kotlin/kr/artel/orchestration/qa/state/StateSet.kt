package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import java.time.Instant

/**
 * 한 게임 인스턴스의 마지막 관측 상태(`STATE_SET`, ARTEL-1296).
 *
 * 계약은 `artel/docs/architecture/2026-10-29-state-set-contract.md` 다. 여기는 그 v0.1 이고, SDK 를 바꾸지 않고
 * 지금 `pulse`(schema 3)만으로 할 수 있는 만큼 한다.
 *
 * - **stream** 은 SDK WebSocket 연결 하나다. 연결이 바뀌면 이전 값은 전부 [ObservationStatus.STALE] 로 남기고,
 *   whole `pulse` 가 올 때까지 델타를 적용하지 않는다(`AWAITING_WHOLE`). 연결 안에서는 순서와 유실이 없고,
 *   SDK 는 전송 실패 뒤 whole 을 다시 보낸다. 그래서 v0.1 은 `seq` 를 검사하지 않는다 — `pulse` 의 `id` 는
 *   메시지 종류가 함께 쓰는 번호라 그 자리에 쓸 수 없다.
 * - **entityId** 는 `<streamId>:<SDK id>` 다. SDK id 는 런타임 instance id 라 연결을 넘으면 같은 객체라고
 *   말할 수 없다. 경로나 selector 로 수명을 잇지 않는다 — 다음 카드가 같은 자리에서 태어난다.
 * - **scope** 는 `persistent`(DontDestroyOnLoad), `scene:<이름>#<세대>`, 그리고 static 의 `runtime` 이다. 세대는
 *   활성 씬 이름이 바뀔 때 오른다. 같은 이름의 씬을 다시 로드하면서 이름이 그대로면 세대가 안 오른다(SDK 가
 *   씬 로드 세대를 보내야 갈린다).
 * - **whole** 은 모든 값을 일단 stale 로 두고 다시 관측된 것만 [ObservationStatus.KNOWN] 으로 되돌린다.
 *   `gaps` 가 없는 완전한 whole 이면 다시 관측되지 않은 객체를 지운다. 잘린 whole 이면 지우지 않고 stale 로
 *   남긴다 — 안 걸은 것과 없는 것은 걷는 쪽만 가린다. static 과 멤버는 whole 에 안 실렸다고 지우지 않는다.
 *   관측 대상이 아닌 값은 있는지 없는지 단정할 수 없다.
 * - **delta** 는 실려 온 것만 바꾼다. 생략은 유지이고, `false`·`0`·빈값·`null` 은 실제 값이다. 꺼진 객체는
 *   지우지 않고 `active=false` 로 둔다. 델타에서 지우는 근거는 `gone` 하나다.
 *
 * `pulse` 하나는 [observe] 안에서 통째로 적용되고 [snapshot] 과 같은 lock 을 쓴다. 읽는 쪽은 적용 중간을 못 본다.
 *
 * SDK 가 세대와 번호를 보내면(ARTEL-1300, SDK 쪽 ARTEL-1299) 계약 문서의 `순서와 복구` 를 그대로 따른다.
 *
 * - `runtime` 이 바뀌면 쥔 상태를 버린다. 프로세스를 넘으면 객체 id 가 같은 객체를 뜻하지 않는다.
 * - stream 은 연결 대신 SDK 의 `stream` 으로 가른다. 같은 연결에서 감시를 다시 켜도 새 stream 이다.
 * - 같은 stream 의 델타는 `seq == last + 1` 일 때만 적용한다. 이미 받은 번호는 버리고, 빈자리면 whole 을 기다리며
 *   [ObserveOutcome.GAP_DETECTED] 를 한 번 돌려준다 — 부른 쪽이 SDK 에 `resync_readings` 를 청한다.
 * - `scenes` 의 handle 이 바뀐 씬은 이름이 같아도 세대를 올린다. 없으면 활성 씬 이름이 바뀔 때 올리던 v0.1 규칙이다.
 */
class StateSet(val gameInstanceId: Long) {

    private var streamKey: String? = null
    private var runtime: String? = null
    private var lastSeq: Long? = null
    private var seqGaps = 0
    private var duplicates = 0
    private var runtimeChanges = 0
    /** 씬 이름에서 마지막으로 본 Unity handle. 바뀌면 그 이름의 세대를 올린다. */
    private val sceneHandles = HashMap<String, Long>()
    private var streamNumber = 0
    private var sync = SyncState.AWAITING_WHOLE
    private var stateVersion = 0L
    private var ignoredDeltas = 0L
    private var reading: Long? = null
    private var frame: Long? = null
    private var activeScene: String? = null
    private val sceneGenerations = HashMap<String, Int>()
    private var coverage: Coverage? = null
    private val entities = LinkedHashMap<String, EntityState>()
    private val statics = LinkedHashMap<String, StaticState>()
    /** `gone` 이 대는 `씬/selector` 주소에서 지금 그 자리의 entityId 로. */
    private val entityByAddress = HashMap<String, String>()

    private val streamId: String? get() = if (streamNumber == 0) null else "s$streamNumber"

    @Synchronized
    fun observe(connectionId: String, pulse: StatePulse, receivedAt: Instant): ObserveOutcome {
        if (pulse.runtime != null && runtime != null && pulse.runtime != runtime) forgetRuntime()
        if (pulse.runtime != null) runtime = pulse.runtime
        val key = pulse.stream?.let { "sdk:$it" } ?: "connection:$connectionId"
        if (key != streamKey) beginStream(key)

        val seq = pulse.seq
        val last = lastSeq
        if (seq != null && last != null && sync == SyncState.SYNCED) {
            if (seq <= last) {
                duplicates += 1
                return ObserveOutcome.IGNORED_DUPLICATE
            }
            if (seq != last + 1 && !pulse.whole) {
                // 빈자리 뒤의 델타는 놓친 차이 위에 얹히므로 적용하지 않는다. 다음 whole 이 기준을 다시 맞춘다.
                seqGaps += 1
                sync = SyncState.AWAITING_WHOLE
                ignoredDeltas += 1
                lastSeq = seq
                return ObserveOutcome.GAP_DETECTED
            }
        }
        if (!pulse.whole && sync != SyncState.SYNCED) {
            ignoredDeltas += 1
            if (seq != null) lastSeq = seq
            return ObserveOutcome.IGNORED_AWAITING_WHOLE
        }

        noteSceneHandles(pulse.scenes)
        enterScene(pulse.scene, bumpOnNameChange = pulse.scenes == null)
        val operations = operationsOf(pulse, receivedAt)
        if (pulse.whole) markAllStale()
        operations.forEach(::applyOperation)
        if (pulse.whole) {
            val complete = pulse.gaps.isNullOrEmpty()
            if (complete) pruneUnobserved().forEach(::applyOperation)
            sync = SyncState.SYNCED
        }

        reading = pulse.reading ?: reading
        frame = pulse.frame ?: frame
        if (seq != null) lastSeq = seq
        coverage = coverageAfter(pulse)
        stateVersion += 1
        return ObserveOutcome.APPLIED
    }

    @Synchronized
    fun snapshot(spec: ObservationSpec): StateSnapshot {
        val activeScope = activeScene?.let(::scopeOf)
        val memberNames = spec.members?.toSet()
        val staticNames = spec.statics?.toSet()
        val matchedNames = HashSet<String>()

        val selected = entities.values
            .filter { entity -> spec.coversEntityScope(entity.scope, activeScope) }
            .filter { entity -> spec.includeInactive || entity.active }
            .filter { entity -> spec.includeStale || entity.status != ObservationStatus.STALE }
            .filter { entity -> spec.paths == null || spec.paths.any { prefix -> entity.path?.startsWith(prefix) == true } }
            .sortedWith(compareBy({ it.path ?: "" }, { it.entityId }))

        val shown = selected.take(spec.maxEntities.coerceAtLeast(0)).map { entity ->
            val members = entity.members.filterKeys { memberId ->
                val name = memberId.substringBefore('#')
                (memberNames == null || name in memberNames).also { kept -> if (kept) matchedNames += name }
            }
            entity.view(members)
        }

        val staticViews = if (!spec.coversRuntime()) emptyList() else statics.values
            .filter { entry ->
                val name = entry.memberId.substringBefore('@')
                (staticNames == null || name in staticNames).also { kept -> if (kept) matchedNames += name }
            }
            .map { entry -> StaticView(entry.memberId, entry.holderPath, entry.observation) }

        val requested = (memberNames.orEmpty() + staticNames.orEmpty())
        return StateSnapshot(
            gameInstanceId = gameInstanceId,
            stateVersion = stateVersion,
            streamId = streamId,
            runtime = runtime,
            sync = sync,
            reading = reading,
            frame = frame,
            scene = activeScene,
            sceneScope = activeScope,
            coverage = coverage,
            entities = shown,
            statics = staticViews,
            unobserved = requested.filterNot { it in matchedNames }.sorted(),
            omittedEntities = selected.size - shown.size,
            ignoredDeltas = ignoredDeltas,
            seqGaps = seqGaps,
            duplicates = duplicates,
            runtimeChanges = runtimeChanges,
        )
    }

    private fun beginStream(key: String) {
        streamKey = key
        lastSeq = null
        streamNumber += 1
        sync = SyncState.AWAITING_WHOLE
        markAllStale()
        // 주소 색인은 새 stream 의 entityId 로 다시 채워진다. 옛 stream 의 객체를 `gone` 으로 지울 일은 없다.
        entityByAddress.clear()
    }

    /** 프로세스가 바뀌었다. 옛 프로세스의 객체와 값, 씬 세대를 버린다 — stale 로도 남기지 않는다. */
    private fun forgetRuntime() {
        runtimeChanges += 1
        entities.clear()
        statics.clear()
        entityByAddress.clear()
        sceneHandles.clear()
        sceneGenerations.clear()
        activeScene = null
        coverage = null
        sync = SyncState.AWAITING_WHOLE
    }

    private fun noteSceneHandles(scenes: List<StatePulseScene>?) {
        for (loaded in scenes.orEmpty()) {
            val name = loaded.name ?: continue
            val handle = loaded.handle ?: continue
            if (sceneHandles[name] == handle) continue
            sceneHandles[name] = handle
            sceneGenerations[name] = (sceneGenerations[name] ?: 0) + 1
        }
    }

    private fun enterScene(scene: String?, bumpOnNameChange: Boolean) {
        if (scene == null || scene == activeScene) return
        if (bumpOnNameChange) sceneGenerations[scene] = (sceneGenerations[scene] ?: 0) + 1
        activeScene = scene
    }

    private fun scopeOf(sceneName: String): String =
        if (sceneName == PERSISTENT_SCENE) PERSISTENT_SCOPE else "scene:$sceneName#${sceneGenerations[sceneName] ?: 1}"

    private fun operationsOf(pulse: StatePulse, receivedAt: Instant): List<StateOperation> {
        val operations = ArrayList<StateOperation>()
        val stamp = Stamp(frame = pulse.frame, reading = pulse.reading, receivedAt = receivedAt)

        for ((objects, isActive) in listOf(pulse.active to true, pulse.deactive to false)) {
            for (pulseObject in objects) {
                val sceneName = pulseObject.scene ?: pulse.scene ?: activeScene ?: ""
                val address = "$sceneName/${pulseObject.selector ?: pulseObject.path ?: ""}"
                val entityId = pulseObject.id?.let { "$streamId:$it" } ?: "$streamId:@$address"
                operations += StateOperation.UpsertEntity(
                    entityId = entityId,
                    address = address,
                    scope = scopeOf(sceneName),
                    sdkId = pulseObject.id,
                    path = pulseObject.path,
                    selector = pulseObject.selector,
                    tag = pulseObject.tag,
                    active = isActive,
                    world = pulseObject.world,
                    rect = pulseObject.rect,
                    reading = pulse.reading,
                )
                val fragments = listOf(
                    "text" to pulseObject.text,
                    "offers" to pulseObject.offers,
                    "ui" to pulseObject.ui,
                    "engine" to pulseObject.engine,
                    "uitk" to pulseObject.uitk,
                )
                for ((name, node) in fragments) {
                    if (node != null) operations += StateOperation.ReplaceFragment(entityId, name, stamp.known(node))
                }
                for (member in pulseObject.spreadMembers()) {
                    val memberId = "${member.on ?: ""}::${member.member ?: ""}#${member.among ?: 0}"
                    operations += StateOperation.SetMember(entityId, memberId, stamp.of(member.value, member.count, member.unread))
                }
            }
        }

        for (entry in pulse.statics) {
            val memberId = "${entry.declaring ?: ""}::${entry.member ?: ""}" + (entry.instance?.let { "@$it" } ?: "")
            operations += StateOperation.SetStatic(memberId, entry.path, stamp.of(entry.value, entry.count, entry.unread))
        }

        for (address in pulse.gone) {
            entityByAddress[address]?.let { operations += StateOperation.RemoveEntity(it) }
        }
        return operations
    }

    /**
     * 완전한 whole 뒤에 다시 관측되지 않은 객체를 지운다. 객체가 하나도 다시 관측되지 않은 scope 는 통째로
     * 지우고(언로드된 씬), 남은 scope 에서는 객체만 지운다.
     */
    private fun pruneUnobserved(): List<StateOperation> {
        val observedScopes = entities.values.filter { it.status == ObservationStatus.KNOWN }.map { it.scope }.toSet()
        val unloadedScopes = entities.values.map { it.scope }.toSet() - observedScopes
        val removals = ArrayList<StateOperation>()
        unloadedScopes.forEach { removals += StateOperation.RemoveScope(it) }
        entities.values
            .filter { it.status == ObservationStatus.STALE && it.scope !in unloadedScopes }
            .forEach { removals += StateOperation.RemoveEntity(it.entityId) }
        return removals
    }

    private fun applyOperation(operation: StateOperation) {
        when (operation) {
            is StateOperation.UpsertEntity -> {
                val entity = entities.getOrPut(operation.entityId) { EntityState(operation.entityId) }
                entity.scope = operation.scope
                entity.sdkId = operation.sdkId ?: entity.sdkId
                entity.path = operation.path ?: entity.path
                entity.selector = operation.selector ?: entity.selector
                entity.tag = operation.tag ?: entity.tag
                entity.world = operation.world ?: entity.world
                entity.rect = operation.rect ?: entity.rect
                entity.active = operation.active
                entity.status = ObservationStatus.KNOWN
                entity.observedReading = operation.reading ?: entity.observedReading
                entityByAddress[operation.address] = operation.entityId
            }
            is StateOperation.SetMember -> entities[operation.entityId]?.members?.set(operation.memberId, operation.observation)
            is StateOperation.ReplaceFragment -> entities[operation.entityId]?.fragments?.set(operation.name, operation.observation)
            is StateOperation.SetStatic -> statics[operation.memberId] =
                StaticState(operation.memberId, operation.holderPath, operation.observation)
            is StateOperation.RemoveEntity -> removeEntity(operation.entityId)
            is StateOperation.RemoveScope -> entities.values
                .filter { it.scope == operation.scope }
                .map { it.entityId }
                .forEach(::removeEntity)
        }
    }

    private fun removeEntity(entityId: String) {
        entities.remove(entityId)
        entityByAddress.values.removeIf { it == entityId }
    }

    private fun markAllStale() {
        for (entity in entities.values) {
            entity.status = ObservationStatus.STALE
            entity.members.replaceAll { _, observation -> observation.stale() }
            entity.fragments.replaceAll { _, observation -> observation.stale() }
        }
        statics.replaceAll { _, entry -> entry.copy(observation = entry.observation.stale()) }
    }

    private fun coverageAfter(pulse: StatePulse): Coverage {
        val previous = coverage
        if (pulse.whole || previous == null) {
            return Coverage(pulse.watching, pulse.unresolved, pulse.unwatchable, pulse.gaps.orEmpty())
        }
        return Coverage(
            watching = pulse.watching ?: previous.watching,
            unresolved = pulse.unresolved ?: previous.unresolved,
            unwatchable = pulse.unwatchable ?: previous.unwatchable,
            gaps = pulse.gaps ?: previous.gaps,
        )
    }

    companion object {
        const val PERSISTENT_SCENE = "DontDestroyOnLoad"
        const val PERSISTENT_SCOPE = "persistent"
        const val RUNTIME_SCOPE = "runtime"
    }
}

enum class ObserveOutcome {
    APPLIED,
    IGNORED_AWAITING_WHOLE,
    /** 같은 stream 에서 이미 받은 `seq` 다. */
    IGNORED_DUPLICATE,

    /** `seq` 에 빈자리가 있다. whole 을 기다리고, 부른 쪽이 SDK 에 `resync_readings` 를 청한다. */
    GAP_DETECTED,
}

/** 한 `pulse` 를 상태에 옮기는 연산. 계약 문서의 delta 연산 표와 같은 이름이다. */
sealed interface StateOperation {
    data class UpsertEntity(
        val entityId: String,
        val address: String,
        val scope: String,
        val sdkId: Long?,
        val path: String?,
        val selector: String?,
        val tag: String?,
        val active: Boolean,
        val world: JsonNode?,
        val rect: JsonNode?,
        val reading: Long?,
    ) : StateOperation

    data class SetMember(val entityId: String, val memberId: String, val observation: Observation) : StateOperation

    /** `runtime` scope 의 값. `holderPath` 가 있으면 실제 static 이 아니라 그 객체가 쥔 값이다. */
    data class SetStatic(val memberId: String, val holderPath: String?, val observation: Observation) : StateOperation

    data class ReplaceFragment(val entityId: String, val name: String, val observation: Observation) : StateOperation

    data class RemoveEntity(val entityId: String) : StateOperation

    data class RemoveScope(val scope: String) : StateOperation
}

private class EntityState(val entityId: String) {
    var scope: String = ""
    var sdkId: Long? = null
    var path: String? = null
    var selector: String? = null
    var tag: String? = null
    var world: JsonNode? = null
    var rect: JsonNode? = null
    var active: Boolean = true
    var status: ObservationStatus = ObservationStatus.KNOWN
    var observedReading: Long? = null
    val fragments = LinkedHashMap<String, Observation>()
    val members = LinkedHashMap<String, Observation>()

    fun view(members: Map<String, Observation>) = EntityView(
        entityId = entityId,
        sdkId = sdkId,
        scope = scope,
        path = path,
        selector = selector,
        tag = tag,
        active = active,
        status = status,
        observedReading = observedReading,
        world = world,
        rect = rect,
        fragments = LinkedHashMap(fragments),
        members = LinkedHashMap(members),
    )
}

private data class StaticState(val memberId: String, val holderPath: String?, val observation: Observation)

private data class Stamp(val frame: Long?, val reading: Long?, val receivedAt: Instant) {
    fun known(value: JsonNode) = Observation(
        status = ObservationStatus.KNOWN, value = value,
        observedFrame = frame, sourceReading = reading, receivedAt = receivedAt,
    )

    /** `unread` 가 오면 못 읽은 것, `count` 만 오면 컬렉션의 개수, 아무것도 없으면 게임이 `null` 을 읽은 것이다. */
    fun of(value: JsonNode?, count: Int?, unread: String?): Observation = when {
        unread != null -> Observation(
            status = ObservationStatus.UNREAD, reason = unread,
            observedFrame = frame, sourceReading = reading, receivedAt = receivedAt,
        )
        value == null && count != null -> known(IntNode(count)).copy(count = count)
        else -> known(value ?: NullNode.instance)
    }
}

private fun Observation.stale(): Observation = copy(status = ObservationStatus.STALE)

private fun ObservationSpec.coversRuntime(): Boolean = scopes == null || StateSet.RUNTIME_SCOPE in scopes

private fun ObservationSpec.coversEntityScope(scope: String, activeScope: String?): Boolean {
    if (scopes == null) return true
    return scopes.any { requested ->
        when (requested) {
            StateSet.PERSISTENT_SCOPE -> scope == StateSet.PERSISTENT_SCOPE
            ObservationSpec.ACTIVE_SCENE -> scope == activeScope
            ObservationSpec.ALL_SCENES -> scope.startsWith("scene:")
            else -> scope == requested
        }
    }
}
