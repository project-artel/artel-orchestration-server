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
 * **조작 가능한 객체의 켜짐/꺼짐만** 본다. 고른 것과 버린 것:
 *
 * | 후보 | 왜 버렸나 |
 * |---|---|
 * | 활성 오브젝트 **전체**의 집합 | 화면이 폭발한다. 적·카드·탄환이 하나 생기고 죽을 때마다 새 화면이 된다. 실측 전투 씬은 손패 장수만으로도 `discriminator` 가 매 초 바뀐다 |
 * | 감시 멤버 **값**까지 | 더 나쁘다. `turn=1` 과 `turn=2` 가 다른 화면이 되어 화면 수가 플레이 길이에 비례한다 |
 * | 씬 이름만 (= 화면을 안 가름) | 이 기능이 존재하는 이유를 못 푼다. `Canvas/continue` 켜짐과 꺼짐이 한 화면이면 "continue 를 눌러라"는 TC 가 절반의 경우에 실패하고 agent 가 그것을 결함으로 보고한다 |
 * | **조작 가능한 객체의 켜짐/꺼짐** (고름) | 화면이 존재하는 이유와 정확히 같은 축이다 — 여기서 무슨 스텝을 실행할 수 있는가. 게임플레이 오브젝트의 생멸도, 값의 변화도 `discriminator` 를 흔들지 않는다 |
 *
 * "조작 가능한 객체" 는 두 출처의 합집합이다:
 * - `pulse` 가 `offers` 를 실어 준 객체 — 그 오브젝트가 **지금** 무엇에 응답하는지 SDK 가 말한 것
 * - 그 씬 `capability.control_selector` 가 지목한 객체 — `evidence` 가 컨트롤이라고 말한 것
 *
 * 둘 다 필요하다. `offers` 만 쓰면 옛 SDK(그 칸을 안 보낸다)에서 `discriminator` 가 통째로 비고,
 * `control_selector` 만 쓰면 `evidence` 가 놓친 팝업이 안 갈린다.
 *
 * ### 임계값이 틀렸을 때 나오는 증상
 *
 * - **너무 민감** — 한 씬의 화면 수가 수십을 넘고
 *   [ScreenObservationService.MAX_SCREENS_PER_SCENE] 경고가 로그를 채운다. 다이어그램이
 *   읽을 수 없어지고 캡처가 행마다 튄다. 원인은 대개 게임플레이 오브젝트가 `offers` 를 달고
 *   오는 것이다 — 그때는 조작 가능 판정을 `control_selector` 쪽으로 좁힌다
 * - **너무 둔함** — 켜짐/꺼짐이 다른데 한 화면으로 뭉쳐 TC 가 절반씩 실패한다. 원인은 대개
 *   그 컨트롤에 기능 행이 없고 SDK 도 `offers` 를 안 보내는 것이다. 그때는 `discriminator` 를 활성
 *   오브젝트 집합 쪽으로 넓히되, 넓히는 순간 위쪽 증상이 온다
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

    /** `offers` 를 한 번이라도 실어 온 객체. 꺼진 뒤에도 조작 가능한 자리였다는 사실은 남는다. */
    private val advertised = HashSet<String>()

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
        if (reading.whole) {
            held.clear()
            advertised.clear()
        }
        reading.scene?.let { scene = it }

        for ((live, arrived) in listOf(true to reading.active, false to reading.deactive)) {
            for (obj in arrived) {
                val selector = obj.key ?: continue
                val key = "${obj.scene ?: reading.scene ?: ""}/$selector"
                held[key] = live
                if (obj.interactive) advertised += key
            }
        }
    }

    /**
     * 지금 상태의 `discriminator`. [controlSelectors] 는 이 씬 기능들이 지목한 컨트롤이다.
     *
     * selector 로 정렬한다. jsonb 배열은 순서가 있어, 정렬하지 않으면 같은 화면이 `pulse` 마다 다른
     * `discriminator` 가 되고 `uk_screen_discriminator` 가 매번 새 행을 앉힌다.
     */
    fun discriminate(controlSelectors: Set<String>): ScreenDiscriminator {
        val prefix = "${scene ?: ""}/"
        val entries = held.asSequence()
            .filter { (key, _) -> key.startsWith(prefix) }
            .mapNotNull { (key, live) ->
                val selector = key.removePrefix(prefix)
                val interactive = key in advertised || selector in controlSelectors
                if (interactive) ScreenDiscriminatorEntry(selector, live) else null
            }
            .sortedBy { it.selector }
            .toList()
        return ScreenDiscriminator(entries)
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

/**
 * 이 화면임을 판정하는 `pulse` 관측 조건. `screen.discriminator` 에 그대로 앉는다.
 *
 * `[{"selector":"Canvas[2]/continue[1]","active":true}]`
 */
@JvmInline
value class ScreenDiscriminator(val entries: List<ScreenDiscriminatorEntry>) {
    /** 지금 켜져 있는 컨트롤. `screen_capability` 가 이 집합에서 나온다. */
    val activeSelectors: Set<String> get() = entries.filter { it.active }.map { it.selector }.toSet()
}

data class ScreenDiscriminatorEntry(val selector: String, val active: Boolean)
