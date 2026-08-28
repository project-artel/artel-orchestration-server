package kr.artel.orchestration.contentmap.observe

/**
 * 한 게임 인스턴스의 `pulse` 를 `fold` 한 상태. **화면을 가르는 임계값이 여기 있다.**
 *
 * ## `fold` 규칙
 *
 * 전량 `pulse`(`whole=true`)는 교체하고 델타는 얹는다. **말하지 않은 객체는 있던 자리를 지킨다** —
 * 없다는 것이 소식이 아니고, 달라졌다면 `pulse` 가 실어 왔을 것이다. agent-server 의
 * `PulseMemory.apply` 와 글자 그대로 같은 규칙이다. 이 채널의 소비자가 둘인데 델타를 다르게
 * 읽으면, 화면이 갈린 이유를 두 쪽에서 다르게 설명하게 되고 어느 쪽이 틀렸는지 가릴 수 없다.
 *
 * 객체를 `씬/selector` 로 키잡는 것도 같은 이유다. 씬이 바뀌면 전량 `pulse` 가 오지만, 오지 않는
 * 경로(전달 유실 뒤 델타만 도착)에서 이전 씬의 객체가 남아 `discriminator` 를 오염시킨다. 씬을 키에 넣어
 * 두면 그때도 현재 씬의 객체만 세어진다.
 *
 * ## 무엇을 다른 화면으로 보는가 — 임계값
 *
 * **목록에 있는 selector 의 켜짐/꺼짐만** 본다. 고른 것과 버린 것:
 *
 * | 후보 | 왜 버렸나 |
 * |---|---|
 * | 활성 오브젝트 **전체**의 집합 | 화면이 폭발한다. 적·카드·탄환이 하나 생기고 죽을 때마다 새 화면이 된다. 실측 전투 씬은 손패 장수만으로도 `discriminator` 가 매 초 바뀐다 |
 * | 감시 멤버 **값**까지 | 더 나쁘다. `turn=1` 과 `turn=2` 가 다른 화면이 되어 화면 수가 플레이 길이에 비례한다 |
 * | 씬 이름만 (= 화면을 안 가름) | 이 기능이 존재하는 이유를 못 푼다. `Canvas/continue` 켜짐과 꺼짐이 한 화면이면 "continue 를 눌러라"는 TC 가 절반의 경우에 실패하고 agent 가 그것을 결함으로 보고한다 |
 * | 조작 가능한 객체 **전부**의 켜짐/꺼짐 | 기본값이 **넣는 쪽**이라 처음 보는 객체는 규칙이 없어서 그냥 들어간다. 실측 `TurnBattleScene` 이 화면 29행까지 올라 [ScreenObservationService.MAX_SCREENS_PER_SCENE] 32 코앞이었다 |
 * | **목록에 있는 selector 의 켜짐/꺼짐** (고름, ARTEL-654) | 화면이 존재하는 이유와 정확히 같은 축이면서, 처음 보는 것이 화면을 늘리지 못한다. 게임마다 답이 달라도 코드가 아니라 목록이 다르다 |
 *
 * 목록은 씬마다 `scene_screen_selector` 에 산다([ScreenSelectorWhitelist]). 씨앗은
 * `capability.control_selector` 이고, 심는 자리는 `ScreenObservationService.seededWhitelist` 다.
 *
 * `pulse` 의 `offers` 는 이제 `discriminator` 를 정하지 않는다. SDK 가 광고한다는 것은 "지금 무엇에
 * 응답하는가" 이지 "이것이 화면을 식별한다" 가 아니고, 그 둘을 같게 놓은 것이 화면이 29행까지
 * 오른 이유였다. `offers` 를 처음 보는 selector 를 목록 후보로 agent 에게 물어보는 데 쓰는 것은
 * ARTEL-655 다.
 *
 * ### 임계값이 틀렸을 때 나오는 증상
 *
 * - **너무 민감** — 한 씬의 화면 수가 수십을 넘고
 *   [ScreenObservationService.MAX_SCREENS_PER_SCENE] 경고가 로그를 채운다. 다이어그램이
 *   읽을 수 없어지고 캡처가 행마다 튄다. 원인은 목록에 든 항목이 너무 넓은 것이다 — `subtree`
 *   항목이 그 아래 스폰되는 프리팹까지 삼켰거나, `path` 항목이 인덱스마다 다른 인스턴스에
 *   맞았거나. 그때는 그 항목을 좁히거나 `screen_defining=false` 로 구멍을 낸다
 * - **너무 둔함** — 켜짐/꺼짐이 다른데 한 화면으로 뭉쳐 TC 가 절반씩 실패한다. 원인은 그
 *   컨트롤이 목록에 없는 것이다. 그때는 항목을 더하되, **더하는 것은 소급되지 않는다** — 이미
 *   뭉친 과거 화면은 그 값이 기록에 없어 복원할 수 없고, 다음 관측부터 갈린다
 *
 * ### 정착(settling)
 *
 * 같은 `discriminator` 가 [SETTLE_READINGS] 회 **연속** 관측돼야 화면으로 굳는다. `pulse` 는 초당 여러 번
 * 오고 전이 중간 프레임은 반쯤 지어진 UI 를 보여준다 — 그 한 프레임이 화면 행과 전이 두 개를
 * 만들면 재현 경로에 아무도 본 적 없는 화면이 낀다. 사람이 한 프레임도 못 본 화면은 화면이
 * 아니다.
 *
 * 대가: 정확히 한 `pulse` 동안만 존재하는 화면은 기록되지 않는다. 2 를 고른 것은 그 대가가 가장
 * 작은 자리라서다 — 1 은 정착이 없는 것이고, 3 이상은 빠른 전이(로딩 → 타이틀)를 통째로
 * 삼킨다.
 */
class ScreenFold {

    /** 마지막 `pulse` 가 말한 씬. */
    var scene: String? = null
        private set

    /** `씬/selector` → 켜져 있나. */
    private val held = LinkedHashMap<String, Boolean>()

    private var pending: ScreenDiscriminator? = null
    private var pendingRuns = 0

    /**
     * 마지막 [apply] 가 만든 변화. 제안의 `changes` 가 이것이다 (ARTEL-655).
     *
     * 현재 씬의 객체만 담는다. 씬을 넘는 순간 이전 씬의 객체가 통째로 변화로 실려 나가면, 답하는
     * 쪽은 이 씬에서 무엇이 달라졌는지가 아니라 씬이 바뀌었다는 사실만 읽게 된다.
     */
    var lastChanges: List<ScreenSelectorChange> = emptyList()
        private set

    /** 이 씬에서 selector 하나를 몇 개의 `pulse` 에서 봤나. 씬이 바뀌면 버린다. */
    private val readingsSeen = HashMap<String, Int>()

    /** 이 씬에서 같은 경로로 접히는 selector 원문들. `Card(Clone)[37]` 과 `[38]` 이 한 자리에 모인다. */
    private val valuesByPath = HashMap<String, MutableSet<String>>()

    private var statsScene: String? = null

    /** 마지막으로 굳은 `discriminator`. 같은 것이 다시 굳어도 그것은 재방문이 아니라 체류다. */
    var settled: ScreenDiscriminator? = null
        private set

    /** 마지막으로 굳은 `discriminator` 가 앉은 화면 행. 전이의 출발점이다. */
    var settledScreenId: Long? = null
        private set

    /**
     * 그 직전에 굳었던 화면 행. 제안이 "이전 화면" 으로 싣는 값이다 (ARTEL-655).
     *
     * 답하는 쪽은 게임을 모르므로 지금 화면만 보여 주면 무엇이 달라졌는지 판단할 근거가 없다.
     * 어디서 왔는가가 그 근거의 절반이다.
     */
    var previousScreenId: Long? = null
        private set

    /** 마지막으로 굳은 화면이 속한 씬 행. 전이가 씬을 넘었는지 여기서 판정한다. */
    var settledSceneId: Long? = null
        private set

    fun apply(reading: PulseReading) {
        if (reading.whole) held.clear()
        reading.scene?.let { scene = it }
        if (statsScene != scene) {
            // 통계는 씬 안에서만 뜻이 있다. 씬을 넘으면 "이 씬에서 몇 번 봤나" 가 다른 씬의 수를
            // 물려받아 답하는 쪽이 처음 보는 것을 오래된 것으로 읽는다.
            statsScene = scene
            readingsSeen.clear()
            valuesByPath.clear()
        }

        val changes = ArrayList<ScreenSelectorChange>()
        val currentScene = scene ?: ""
        for ((live, arrived) in listOf(true to reading.active, false to reading.deactive)) {
            for (obj in arrived) {
                val selector = obj.key ?: continue
                val objectScene = obj.scene ?: reading.scene ?: ""
                val key = "$objectScene/$selector"
                val was = held.put(key, live)
                if (objectScene != currentScene || was == live) continue
                changes.add(ScreenSelectorChange(selector, was, live))
                valuesByPath.getOrPut(indexFreePathOf(selector)) { HashSet() }.add(selector)
            }
        }
        lastChanges = changes

        val prefix = "$currentScene/"
        for (key in held.keys) {
            if (!key.startsWith(prefix)) continue
            val selector = key.removePrefix(prefix)
            readingsSeen[selector] = (readingsSeen[selector] ?: 0) + 1
        }
        if (readingsSeen.size > MAX_TRACKED_SELECTORS) {
            // 이름이 매번 바뀌는 게임에서 이 두 맵이 플레이 길이만큼 자란다. 그것이 ARTEL-654 가
            // "여러 관측에 걸친 등장 횟수" 를 판정 규칙으로 쓰지 않기로 한 이유이고, 통계로 쓸
            // 때도 같은 값을 치른다. 통째로 버리고 다시 센다 — 통계가 0 부터 다시 세는 것은
            // 제안이 조금 덜 친절해지는 것뿐이고, 새는 것보다 낫다.
            readingsSeen.clear()
            valuesByPath.clear()
        }
    }

    /**
     * 목록에도 제외에도 없는 selector 를 방금 봤나 (ARTEL-655).
     *
     * 후보는 **이 `pulse` 에서 상태가 달라진 것**뿐이다. 지금 씬에 있는 것 전부로 하면 첫 전량
     * `pulse` 뒤로도 같은 후보가 계속 나오고, 실측 `TurnBattleScene` 은 그 집합이 59 개다. 달라진
     * 것으로 좁히면 combine 패널이 열리는 순간 그 셋만 후보가 된다 — 그리고 그 순간이 답하는
     * 쪽이 캡처에서 차이를 볼 수 있는 유일한 자리다.
     *
     * [ScreenSelectorWhitelist.covers] 로 거른다. [ScreenSelectorWhitelist.defines] 로 거르면
     * **명시적 제외 항목이 매번 다시 후보가 된다** — 이미 "안 가른다" 는 답을 받은 것을 계속 다시
     * 묻게 되고, 그것이 이 기능이 막으려던 바로 그 반복이다.
     */
    fun unknownCandidates(whitelist: ScreenSelectorWhitelist): List<ScreenSelectorCandidate> =
        lastChanges.asSequence()
            .map { it.selector }
            .distinct()
            .filterNot { whitelist.covers(it) }
            .map { candidateOf(it, inWhitelist = false) }
            .toList()

    /**
     * 지금 이 씬에서 화면을 가르고 있는 selector (ARTEL-655).
     *
     * 화면 상한에 닿았을 때 **무엇을 뺄지** 묻는 제안의 후보다. 상한에 닿았다는 것은 목록이 너무
     * 잘다는 뜻이므로, 물어볼 대상은 목록 밖이 아니라 목록 안이다.
     */
    fun whitelistedCandidates(whitelist: ScreenSelectorWhitelist): List<ScreenSelectorCandidate> =
        selectorsInScene()
            .filter { (selector, _) -> whitelist.defines(selector) }
            .map { (selector, _) -> candidateOf(selector, inWhitelist = true) }
            .toList()

    private fun candidateOf(selector: String, inWhitelist: Boolean): ScreenSelectorCandidate {
        val path = indexFreePathOf(selector)
        return ScreenSelectorCandidate(
            selector = selector,
            path = path,
            active = held["${scene ?: ""}/$selector"] ?: false,
            instancesInReading = selectorsInScene().count { (other, _) -> indexFreePathOf(other) == path },
            readingsSeenInScene = readingsSeen[selector] ?: 0,
            distinctValuesObserved = valuesByPath[path]?.size ?: 0,
            inWhitelist = inWhitelist,
        )
    }

    /** 이 씬에서 지금까지 본 selector 원문 전부. 목록을 고치는 프레임이 대상을 검증하는 데 쓴다. */
    fun observedSelectors(): Set<String> =
        selectorsInScene().map { (selector, _) -> selector }.toSet()

    /**
     * 지금 상태의 `discriminator`. [whitelist] 는 이 씬에서 화면을 식별하는 selector 목록이다.
     *
     * **목록에 없는 selector 는 처음 보는 것이어도 안 들어간다.** 목록이 비면 `entries` 가 비고, 그
     * 씬의 관측은 전부 화면 한 행에 앉는다 — 오류가 아니다([ScreenSelectorWhitelist]).
     *
     * selector 로 정렬한다. jsonb 배열은 순서가 있어, 정렬하지 않으면 같은 화면이 `pulse` 마다 다른
     * `discriminator` 가 되고 `uk_screen_discriminator` 가 매번 새 행을 앉힌다.
     */
    fun discriminate(whitelist: ScreenSelectorWhitelist): ScreenDiscriminator {
        val entries = selectorsInScene()
            .filter { (selector, _) -> whitelist.defines(selector) }
            .map { (selector, live) -> ScreenDiscriminatorEntry(selector, live) }
            .sortedBy { it.selector }
            .toList()
        return ScreenDiscriminator(entries)
    }

    /**
     * 지금 켜져 있는 객체의 selector. `screen_capability` 가 이 집합에서 나온다.
     *
     * `discriminator` 가 아니라 `fold` 에서 읽는다. 둘은 다른 질문에 답한다 — `discriminator` 는
     * "무엇이 이 화면을 식별하나", 이쪽은 "이 화면에서 무엇이 켜져 있었나" 다. 목록에 없는
     * 컨트롤이라고 해서 그 화면이 그 기능을 제공하지 않은 것은 아니므로, 목록을 손대는 것이
     * `screen_capability` 를 조용히 지우면 안 된다.
     */
    fun activeSelectors(): Set<String> =
        selectorsInScene().filter { (_, live) -> live }.map { (selector, _) -> selector }.toSet()

    /** 현재 씬에 속한 객체만. 씬을 넘은 직후 이전 씬의 객체가 남아 있어도 세지 않는다. */
    private fun selectorsInScene(): Sequence<Pair<String, Boolean>> {
        val prefix = "${scene ?: ""}/"
        return held.asSequence()
            .filter { (key, _) -> key.startsWith(prefix) }
            .map { (key, live) -> key.removePrefix(prefix) to live }
    }

    /**
     * 이 `discriminator` 를 지금 화면으로 굳혀야 하는가.
     *
     * 같은 화면에 머무는 동안에는 계속 `false` 다 — 그래서 `observed_count` 가 `pulse` 수가 아니라
     * **방문 수**를 센다. 재방문이 행을 늘리지 않는 것과 같은 규율이다.
     *
     * **판정만 하고 굳히지는 않는다.** 굳히는 것은 적재가 끝난 뒤 [confirm] 이다. 여기서 굳혀
     * 버리면 적재가 실패했을 때 상태만 앞서 나가고, 같은 `discriminator` 가 계속 와도 [settle] 이 늘
     * `false` 를 돌려주어 **그 화면은 영영 앉지 못한다.** 지금 모양에서는 실패 뒤 두 `pulse` 면
     * 다시 시도한다.
     */
    fun settle(candidate: ScreenDiscriminator): Boolean {
        if (candidate == settled) {
            // 이미 굳은 것과 같다. 흔들리다 제자리로 돌아온 경우도 여기다 — 굳은 값이 바뀐 적이
            // 없으므로 전이도 없다.
            pending = null
            pendingRuns = 0
            return false
        }
        if (candidate != pending) {
            pending = candidate
            pendingRuns = 1
            return false
        }
        pendingRuns += 1
        if (pendingRuns < SETTLE_READINGS) return false
        pending = null
        pendingRuns = 0
        return true
    }

    /** 적재가 끝났다. 이 `discriminator` 를 굳히고 다음 전이의 출발점으로 삼는다. */
    fun confirm(candidate: ScreenDiscriminator, screenId: Long, sceneId: Long) {
        if (settledScreenId != null && settledScreenId != screenId) previousScreenId = settledScreenId
        settled = candidate
        settledScreenId = screenId
        settledSceneId = sceneId
    }

    /**
     * 굳은 화면을 잊는다. **접기가 그 행을 지웠을 수 있어서다** (ARTEL-655).
     *
     * `fold_scene_screens` 가 접은 화면은 사라지고, 그 id 를 그대로 들고 있으면 다음 전이가 없는
     * 행을 출발점으로 삼는다. 새 id 로 갈아 끼우지 않고 잊는 것은, 접힌 행의 대표를 여기서 다시
     * 찾는 것이 접기 규칙을 Kotlin 에 한 벌 더 두는 일이기 때문이다 — 그 두 벌이 갈리는 것이
     * `fold_scene_screens` 를 SQL 에 한 벌만 둔 이유다.
     *
     * 대가는 전이 하나다. 다음 `pulse` 둘이 화면을 다시 굳히고, 그때 출발점이 없어 전이가 안
     * 남는다. `held` 는 그대로 두므로 상태를 다시 쌓을 필요는 없다.
     */
    fun forgetSettled() {
        settled = null
        settledScreenId = null
        settledSceneId = null
        previousScreenId = null
        pending = null
        pendingRuns = 0
    }

    companion object {
        /** `discriminator` 가 화면으로 굳기까지 필요한 연속 관측 수. 근거는 클래스 주석의 `정착` 절. */
        const val SETTLE_READINGS = 2

        /** 통계를 들고 있을 selector 수의 상한. 넘으면 통계만 비운다 — 근거는 [apply] 안의 주석. */
        const val MAX_TRACKED_SELECTORS = 2048
    }
}

/** selector 의 형제 index. `Card(Clone)[37]` 의 `[37]`, `CombineSystem[7]/CombineZone[1]` 의 둘 다. */
private val SIBLING_INDEX = Regex("""\[\d+]""")

/**
 * selector 에서 **경로 모든 마디**의 형제 index 를 지운 경로.
 *
 * `CombineSystem[7]/CombineZone[1]/Zone1[0]` → `CombineSystem/CombineZone/Zone1`
 *
 * `scene_screen_selector` 의 `path` · `subtree` 항목이 이 값과 맞대 본다. ARTEL-649 가 잠깐
 * `collection family` 라고 부르던 정규화와 같은 것이다.
 *
 * ## 왜 마지막 마디만 지우지 않는가
 *
 * 스폰되는 것이 잎이라는 보장이 없다. 스폰된 부모 아래의 자식은 **부모 쪽 index** 가 흔들린다 —
 * `Card(Clone)[37]/Cost[0]` 과 `Card(Clone)[38]/Cost[0]` 이 그렇다. 마지막 마디만 지우면 이 둘은
 * `Card(Clone)[37]/Cost` 와 `Card(Clone)[38]/Cost` 라는 **서로 다른** 경로가 되어, `path` 항목 하나로
 * 한 프리팹의 인스턴스 전부를 가리킬 수 없다.
 *
 * ## 다 지우는 대가
 *
 * 조상 이름까지 같은 서로 다른 컨트롤이 한 경로로 접힌다. `path` 항목이 그 둘을 한꺼번에 가리키게
 * 되므로, 하나만 목록에 넣고 싶으면 `selector` 항목을 쓴다. 씨앗이 `selector`
 * 인 이유도 이것이다(`SceneScreenSelectorRepository.seedFromControlSelector`).
 *
 * ## 같은 규칙이 SQL 에도 있다
 *
 * `V60__whitelist_screen_defining_selectors.sql` 의 `screen_defining_selector` 가 쓰는
 * `regexp_replace(selector, '\[[0-9]+\]', '', 'g')` 가 이 정규식과 같은 것이어야 한다. 어긋나면
 * 소급 처리가 접은 화면과 런타임이 앉히는 화면이 다른 규칙을 따르게 되어, 합쳐 놓은 행 옆에
 * 옛 모양의 행이 다시 쌓인다.
 */
fun indexFreePathOf(selector: String): String = SIBLING_INDEX.replace(selector, "")

/**
 * 이 화면임을 판정하는 `pulse` 관측 조건. `screen.discriminator` 에 그대로 앉는다.
 *
 * `[{"selector":"Canvas[2]/continue[1]","active":true}]`
 */
@JvmInline
value class ScreenDiscriminator(val entries: List<ScreenDiscriminatorEntry>)

data class ScreenDiscriminatorEntry(val selector: String, val active: Boolean)
