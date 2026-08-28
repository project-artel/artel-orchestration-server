package kr.artel.orchestration.contentmap.observe

import kr.artel.orchestration.contentmap.config.ActionObservationProperties
import java.time.Instant

/**
 * 한 게임 인스턴스의 액션 채널과 `pulse` 채널을 시간축으로 붙인 상태 (ARTEL-450).
 *
 * ```
 * 액션 채널 (ACTION → ACTION_RESULT)   "이 오브젝트에 클릭을 보냈고 성공했다"      t
 * 상태 채널 (pulse)                    "reading n 에서 이런 것들이 달라졌다"        t+α
 * ```
 *
 * SDK 는 "눌린 것이 실제로 발화했는지"를 상태 채널에 싣지 않는다. 그래서 인과는 읽는 쪽이 세워야
 * 하고, **이 클래스가 그 세우는 규칙 전부다.** DB 를 모르며, 닫힌 창([ClosedActionWindow])만 내놓는다.
 *
 * # 규칙 다섯
 *
 * ## 1. 겨눈 것이 이름으로 남은 액션만 받는다
 *
 * [ActionTarget] 참고. 좌표를 받는 액션(`move_mouse` · `mouse_down` · `mouse_up`)은 무엇을 겨눴는지
 * 아무도 말하지 않았으므로 관측을 만들지 않는다. 실측 한 런의 액션 394 개 중 301 개가 여기서
 * 떨어진다. **그 301 개를 억지로 해석하는 것이 이 기능이 낼 수 있는 가장 비싼 오류다** — 좌표 위에
 * 무엇이 있었는지를 추측해 기록하면, ARTEL-451 이 그 추측으로 기능을 `confirmed` 로 올린다.
 *
 * ## 2. SDK 가 받았다고 답한 액션만 받는다
 *
 * `ACTION_RESULT` 를 기다렸다가, 실패로 오면 창을 버린다([reject]). 거절당한 액션을 `fired=false`
 * 로 적으면 "버튼이 아무 일도 안 했다"가 되는데, 실제로는 **버튼에 닿지도 못했다.** 실측에서
 * 버튼이 아닌 오브젝트(적 · 배경 글자)에 보낸 클릭 4 건이 그렇게 거절당했다.
 *
 * 답을 기다리므로 창은 두 단계로 닫힌다 — `pulse` 를 다 모으는 것([OpenWindow.completed])과 답이
 * 오는 것([OpenWindow.succeeded])이 각각 일어나고, 둘 다 된 뒤에야 관측이 나간다. 답이 끝내 안
 * 오면 다음 액션이 그 창을 버린다.
 *
 * ## 3. 창은 세 가지 중 먼저 오는 것으로 닫힌다
 *
 * - `pulse` [ActionObservationProperties.readings] 개
 * - [ActionObservationProperties.windowMillis] 경과
 * - **다음 액션이 나감** ([open] 이 열려 있는 창을 닫는다)
 *
 * 셋째가 규율의 핵심이다. 액션 B 가 A 의 창 안에서 나가면 그 구간의 변화는 원인이 둘이라 가릴 수
 * 없다. 창을 거기서 끊으면 남는 구간은 A 하나만 있던 구간이다. 그래서 이 타임라인은 창을 하나만
 * 연다 — 둘째 창이 열리는 순간 첫째 창은 이미 배타적이지 않다.
 *
 * `pulse` 를 하나도 못 담고 닫힌 창은 버린다. 본 것이 없으면 "아무 일도 없었다"고 말할 수 없다 —
 * 그것은 게임이 조용한 것이 아니라 우리가 눈을 감고 있었던 것이다.
 *
 * ## 4. `reading` 번호로 창을 적는다
 *
 * 두 채널의 시각은 우리 쪽 도착 시각이다. `pulse` 는 샘플링 시점과 도착 시점이 다르고, 실측에서
 * SDK 가 매긴 `reading` 이 30,290 까지 오르는 동안 우리가 받은 것은 14,036 개였다 — 절반이 전달
 * 과정에서 사라진다. 그래서 창의 경계는 우리 시계가 아니라 SDK 의 순번으로 남긴다
 * ([ClosedActionWindow.readingBefore] · [ClosedActionWindow.readingAfter]). 어떤 구간을 봤는지
 * 나중에 다시 세는 사람이 우리 도착 시각을 믿지 않아도 되게.
 *
 * 다만 **순서 판정 자체는 도착 순서로 한다.** 실시간으로는 그것밖에 없다.
 *
 * ## 5. 배경은 뺀다 — 대조군은 같은 게임의 조금 전이다
 *
 * **"뭔가 달라졌다"는 늘 참이다.** 실측 `pulse` 14,489 개 전부가 `changed` 를 비우지 않은 채 왔고,
 * 적 애니메이터 selector 다섯 개가 그 변화의 2 만 건을 혼자 차지한다. 게임은 아무도 안 눌러도
 * 애니메이션을 돌리고 스폰하고 틱을 돈다.
 *
 * 그래서 창 안에서 달라진 것 중 **액션 직전 같은 길이의 구간에서도 달라지고 있던 것은 빼고 센다.**
 * 대조군은 같은 게임의 몇백 밀리초 전이다. 애니메이터는 앞뒤 양쪽에서 흔들리므로 빠지고,
 * 클릭이 켠 튜토리얼 창은 뒤에서만 나타나므로 남는다.
 *
 * 대조군은 항상 [ActionObservationProperties.readings] 개를 채운다. 창이 다음 액션 때문에 일찍
 * 닫혀 더 짧아져도 대조군은 안 줄인다 — 배경으로 치는 것이 늘어나는 쪽이라 판정이 더 보수적으로
 * 기운다.
 *
 * # 이것이 인과가 아니라는 것
 *
 * 위 다섯을 다 지켜도 남는 것은 **상관**이다. 창은 배타적이고 배경은 뺐지만, 마침 그 순간에 끝난
 * 코루틴과 이 액션을 우리는 여전히 가르지 못한다. 그러므로:
 *
 * - `fired=true` 하나만으로 기능을 승격하면 안 된다. 무엇이 달라졌는지
 *   ([ClosedActionWindow.effects])를 기대와 맞대는 것이 ARTEL-451 이고, 그 대조가 승격의 근거다
 * - `fired=false` 는 "버튼이 고장났다"와 "관측 가능한 것이 아무것도 없었다" 둘 다일 수 있다.
 *   `capability_effect.watchable` 을 함께 봐야 갈린다
 *
 * # 락
 *
 * `pulse` 쪽([absorb])은 한 세션의 프레임을 `concatMap` 으로 하나씩 처리하는 경로라 직렬이지만,
 * [open] 과 [reject] 는 각각 agent 인바운드와 SDK 인바운드에서 온다. 셋이 같은 창을 만지므로
 * 전부 `synchronized` 로 묶는다. 구간은 맵 몇 개를 만지는 것뿐이라 `pulse` 처리를 세우지 않는다.
 */
class ActionTimeline(private val properties: ActionObservationProperties) {

    /** instance id → 그 오브젝트가 있던 `(씬, selector)`. `button_click` 의 인자를 이것으로 푼다. */
    private val objectsById = LinkedHashMap<Long, ActionTarget.Control>()

    /** 최근 `pulse` 의 `changed` 집합. 대조군이 여기서 나온다. */
    private val recentChanges = ArrayDeque<Set<String>>()

    /** 열려 있는 창. 규칙 3 에 따라 언제나 최대 하나다. */
    private var openWindow: OpenWindow? = null

    /** 컨트롤별 거절 횟수. 성공한 관측이 나가면 지운다 — 그것이 `attempts` 다. */
    private val rejectedAttempts = HashMap<String, Int>()

    /** 마지막 `pulse` 가 말한 씬. 키 입력의 귀속 씬이 이것이다. */
    var scene: String? = null
        private set

    /** 마지막으로 받은 `reading` 번호. 액션이 나갈 때 `reading_before` 로 집는다. */
    var lastReading: Long? = null
        private set

    /** 마지막으로 굳어 있던 화면 행. 액션이 나갈 때 그 액션이 앉은 화면으로 집는다. */
    var lastScreenId: Long? = null
        private set

    /**
     * `pulse` 하나를 접고, 그 결과 닫힌 관측을 돌려준다.
     *
     * 접기와 창 넣기를 한 메서드로 둔 것은 순서 때문이다. 대조군은 **액션 직전까지의** `changed`
     * 여야 하므로, 창에 넣는 것과 대조군 큐를 미는 것이 뒤집히면 창이 자기 자신을 배경으로 뺀다.
     */
    @Synchronized
    fun absorb(reading: PulseReading, screenId: Long?, now: Instant): List<ClosedActionWindow> {
        val changed = reading.changed.toSet()
        openWindow?.let { window ->
            if (!window.completed) {
                window.collect(changed, reading.reading)
                val elapsed = now.toEpochMilli() - window.action.actedAt.toEpochMilli()
                if (window.readings >= properties.readings || elapsed >= properties.windowMillis) {
                    window.completed = true
                }
            }
        }
        recentChanges.addLast(changed)
        while (recentChanges.size > properties.readings) recentChanges.removeFirst()

        foldObjects(reading, screenId)
        return drain()
    }

    /**
     * 액션 하나가 SDK 로 나갔다. **열려 있던 창을 닫는다** (규칙 3).
     *
     * 겨눈 것이 없는 액션도 반드시 여기로 들어와야 한다. 그 액션 역시 앞선 창의 배타성을 깨기
     * 때문이다 — `move_mouse` 는 관측을 만들지 않지만 마우스를 움직이기는 한다.
     *
     * 아직 답을 못 받은 창은 여기서 버려진다. 그것이 규칙 2 의 대가이고, 답이 안 오는 액션의
     * 관측을 지어내는 것보다 싸다.
     */
    @Synchronized
    fun open(
        requestId: Long,
        target: ActionTarget?,
        method: String,
        params: List<Any?>,
        actedAt: Instant,
    ): List<ClosedActionWindow> {
        val closed = drain()
        openWindow = target?.let {
            OpenWindow(
                action = DispatchedAction(requestId, it, method, params, actedAt, lastScreenId),
                readingBefore = lastReading,
                baseline = recentChanges.flatten().toHashSet(),
            )
        }
        return closed
    }

    /**
     * SDK 가 이 액션을 받았다고 답했다. 창이 이미 `pulse` 를 다 모았으면 다음 [absorb] 가 내보낸다.
     */
    @Synchronized
    fun accept(requestId: Long) {
        openWindow?.takeIf { it.action.requestId == requestId }?.succeeded = true
    }

    /**
     * SDK 가 이 액션을 거절했다. 창을 버리고 재시도로 센다 (규칙 2).
     *
     * 닿지도 못한 액션은 게임에 대해 아무것도 말하지 않는다. 대신 다음에 같은 컨트롤에서 성공하면
     * 그 관측의 `attempts` 가 여기서 센 만큼 올라간다 — **힌트가 나쁜 자리가 그렇게 드러난다.**
     */
    @Synchronized
    fun reject(requestId: Long) {
        val window = openWindow?.takeIf { it.action.requestId == requestId } ?: return
        openWindow = null
        val key = window.action.target.key
        rejectedAttempts[key] = (rejectedAttempts[key] ?: 0) + 1
    }

    /** 이 instance id 가 지금 어느 `(씬, selector)` 인가. 본 적 없으면 null 이다. */
    @Synchronized
    fun controlOf(instanceId: Long): ActionTarget.Control? = objectsById[instanceId]

    /**
     * 전량 `pulse` 는 교체하고 델타는 얹는다. `ScreenFold.apply` 와 같은 규칙이다 — 이 채널의
     * 소비자가 델타를 다르게 읽으면 어느 쪽이 틀렸는지 가릴 수 없다.
     */
    private fun foldObjects(reading: PulseReading, screenId: Long?) {
        if (reading.whole) objectsById.clear()
        reading.scene?.let { scene = it }
        reading.reading?.let { lastReading = it }
        lastScreenId = screenId

        // 넘으면 통째로 비운다. 다음 전량 `pulse` 가 복구하므로 자기 치유되고, 그동안 잃는 것은
        // 해석하지 못한 액션 몇 개다.
        if (objectsById.size > properties.maxTrackedObjects) objectsById.clear()
        for (obj in reading.active.asSequence() + reading.deactive.asSequence()) {
            val instanceId = obj.instanceId ?: continue
            val selector = obj.key ?: continue
            objectsById[instanceId] = ActionTarget.Control(obj.scene ?: reading.scene ?: "", selector)
        }
    }

    /** 조건을 다 갖춘 창을 관측으로 바꿔 내보낸다. 규칙 2 와 3 이 여기서 만난다. */
    private fun drain(): List<ClosedActionWindow> {
        val window = openWindow ?: return emptyList()
        if (!window.completed || window.succeeded != true) return emptyList()
        openWindow = null
        // `pulse` 를 하나도 못 담은 창은 아무것도 말하지 않는다.
        if (window.readings == 0) return emptyList()

        val novel = window.observed - window.baseline
        val key = window.action.target.key
        return listOf(
            ClosedActionWindow(
                target = window.action.target,
                method = window.action.method,
                params = window.action.params,
                actedAt = window.action.actedAt,
                screenId = window.action.screenId,
                attempts = 1 + (rejectedAttempts.remove(key) ?: 0),
                readingBefore = window.readingBefore,
                readingAfter = window.lastReading,
                fired = novel.isNotEmpty(),
                effects = effectsOf(novel, window.action.target.scene, scene),
            )
        )
    }

    /**
     * 새로 달라진 것들을 [ObservedEffect] 로 옮긴다. 상한을 넘으면 자른다.
     *
     * 정렬해서 자른다 — 자르는 자리가 `pulse` 마다 달라지면 같은 조작의 두 관측이 다른 효과를
     * 들게 되고, 기대와 대조하는 쪽이 그 차이를 게임의 차이로 읽는다.
     */
    private fun effectsOf(novel: Set<String>, actedScene: String, currentScene: String?): List<ObservedEffect> =
        novel.asSequence()
            .sorted()
            .take(properties.maxObservedEffects)
            .map { observedEffectOf(it, actedScene, currentScene) }
            .toList()

    private class OpenWindow(
        val action: DispatchedAction,
        val readingBefore: Long?,
        val baseline: Set<String>,
    ) {
        val observed = HashSet<String>()

        /** `pulse` 를 다 모았나 (규칙 3). */
        var completed = false

        /** SDK 가 받았다고 답했나 (규칙 2). null 이면 아직 답이 없다. */
        var succeeded: Boolean? = null

        var readings = 0
            private set

        var lastReading: Long? = null
            private set

        fun collect(changed: Set<String>, reading: Long?) {
            observed += changed
            readings += 1
            if (reading != null) lastReading = reading
        }
    }
}

/**
 * `changed` 항목 하나를 [ObservedEffect] 로 읽는다.
 *
 * 모양은 [ObservedEffect] 의 KDoc 에 있다. **모르는 모양은 통째로 [ObservedEffectKind.OBJECT] 의
 * target 으로 둔다** — SDK 가 표기를 늘려도 항목이 조용히 사라지지 않아야 한다. 사라지면
 * `fired=true` 인데 효과가 빈 행이 생기고, 그것을 본 사람은 우리 파서가 아니라 게임을 의심한다.
 */
internal fun observedEffectOf(changed: String, actedScene: String, currentScene: String?): ObservedEffect {
    if (changed == SCENE_CHANGE_KEY) {
        // 항목 자체는 `"scene"` 이라는 글자 하나뿐이다. 어디서 어디로 갔는지는 타임라인만 알므로
        // 여기서 채운다 — 그 둘이 없으면 이 효과는 기대와 맞댈 수 없다.
        return ObservedEffect(ObservedEffectKind.SCENE, currentScene ?: changed, detail = actedScene)
    }
    val separator = changed.indexOf(OBJECT_FACET_SEPARATOR)
    if (separator < 0) return memberEffect(changed, on = null)

    val path = changed.substring(0, separator)
    val facet = changed.substring(separator + 1)
    if (!facet.contains(MEMBER_SEPARATOR)) {
        return ObservedEffect(ObservedEffectKind.OBJECT, path, detail = facet)
    }
    return memberEffect(facet, on = path)
}

private fun memberEffect(qualified: String, on: String?): ObservedEffect {
    val separator = qualified.indexOf(MEMBER_SEPARATOR)
    if (separator < 0) return ObservedEffect(ObservedEffectKind.OBJECT, qualified, on = on)
    return ObservedEffect(
        kind = ObservedEffectKind.MEMBER,
        target = qualified.substring(0, separator),
        detail = qualified.substring(separator + MEMBER_SEPARATOR.length),
        on = on,
    )
}

/** `changed` 가 씬 자체가 바뀌었다고 말하는 항목. 값이 아니라 이 문자열 하나가 신호다. */
private const val SCENE_CHANGE_KEY = "scene"

/** `TitleScene/Canvas[2]/continue[2]|active` 의 `|`. 경로와 facet 을 가른다. */
private const val OBJECT_FACET_SEPARATOR = '|'

/** `Battle.Turns.TurnBattleSystem::EnemyTurn` 의 `::`. 타입과 멤버를 가른다. */
private const val MEMBER_SEPARATOR = "::"
