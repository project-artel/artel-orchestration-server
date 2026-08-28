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

    /** 마지막으로 굳은 `discriminator`. 같은 것이 다시 굳어도 그것은 재방문이 아니라 체류다. */
    var settled: ScreenDiscriminator? = null
        private set

    /** 마지막으로 굳은 `discriminator` 가 앉은 화면 행. 전이의 출발점이다. */
    var settledScreenId: Long? = null
        private set

    /** 마지막으로 굳은 화면이 속한 씬 행. 전이가 씬을 넘었는지 여기서 판정한다. */
    var settledSceneId: Long? = null
        private set

    fun apply(reading: PulseReading) {
        if (reading.whole) held.clear()
        reading.scene?.let { scene = it }

        for ((live, arrived) in listOf(true to reading.active, false to reading.deactive)) {
            for (obj in arrived) {
                val selector = obj.key ?: continue
                held["${obj.scene ?: reading.scene ?: ""}/$selector"] = live
            }
        }
    }

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
        settled = candidate
        settledScreenId = screenId
        settledSceneId = sceneId
    }

    companion object {
        /** `discriminator` 가 화면으로 굳기까지 필요한 연속 관측 수. 근거는 클래스 주석의 `정착` 절. */
        const val SETTLE_READINGS = 2
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
 * `V58__whitelist_screen_defining_selectors.sql` 의 `screen_defining_selector` 가 쓰는
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
