package kr.artel.orchestration.contentmap.entity

/**
 * 한 QA 런에게 content map 을 얼마나 열어 줄지. `qa_try.run_config.content_map_mode`에 남는다.
 *
 * `KnowledgeMode`와 나란한 두 번째 축이다. 저쪽이 "지식이 실제로 도움이 되는가"를 묻고, 이쪽은
 * "지도가 실제로 도움이 되는가"를 묻는다. 둘을 따로 끌 수 있어야 2×2 — 지도만 · 지식만 · 둘 다 ·
 * 둘 다 없음 — 가 성립한다. 한 스위치로 묶으면 어느 쪽이 이겼는지 영영 못 가른다.
 *
 * - [ON]     — 읽고 쓴다. 기본값이고, 이 값이면 이 변경 이전과 동작이 같다.
 * - [FROZEN] — 읽기만. `CAPABILITY_VERDICT` · `CAPABILITY_DISCOVERED` 는 거부된다. **측정 런이 실제로
 *              쓰는 값이다.**
 * - [OFF]    — 조회가 지도 없는 빌드와 같은 답을 낸다. 쓰기도 막힌다. "지도 없이 돌면 얼마나 하나"의
 *              대조군.
 *
 * ## 기본값 이름이 `LEARNING` 이 아니라 [ON] 인 이유
 *
 * `KnowledgeMode.LEARNING` 은 그 런이 지식창고에 새 사실을 **배워 넣는다**는 뜻이다. 런이 지도에
 * 하는 일은 그것과 다르다 — 이미 적힌 `capability` 가 되는지 확인해 `verification` 을 옮기고,
 * 근거에 없던 것을 봤을 때 행 하나를 더한다. 확인을 학습이라 부르면 이 값이 무엇을 켜는지가
 * 흐려지므로, 두 축의 이름을 맞추는 것보다 각 값이 정확한 쪽을 골랐다.
 *
 * ## [FROZEN] 이 여기에도 있는 이유
 *
 * **반복이 반복이려면 arm 이 자기가 읽는 것을 바꾸면 안 된다.** `CAPABILITY_VERDICT` 와
 * `CAPABILITY_DISCOVERED` 가 지도를 바꾸므로, "지도 있음" arm 을 두 번 돌리면 두 번째 런이 첫 번째
 * 런이 남긴 `verdict` 를 읽는다. 그러면 두 런은 같은 설정이 아니고, 반복 측정이 성립하지 않는다.
 * `KnowledgeMode.FROZEN` 이 "같은 출발점에서 여러 arm 을 돌릴 때 쓴다" 고 적은 것과 같은 이유다.
 *
 * content map 은 그 이유가 **더 세다.** 지식창고에는 `knowledge.scope_id` 라는 격리 축이 있어 실험
 * 런의 쓰기를 운영 지식에서 떼어 놓을 수 있지만(`KnowledgeScope`), `capability` 행은 `content_map`
 * → `game_build` 에 바로 매달려 있고 그런 축이 없다. 그래서 [ON] 으로 돌린 arm 의 `verdict` 하나가
 * 같은 빌드를 쓰는 **모든** arm 의 지도를 바꾼다 — `verification` 이 `unverified` 에서 `confirmed`
 * 로 가고, `origin = observed` 인 행이 새로 선다. [FROZEN] 은 그것을 막는 유일한 수단이다.
 *
 * ## Agent 가 아니라 서버에서 막는 이유
 *
 * `KnowledgeMode` 의 KDoc 이 세운 논거를 그대로 따른다. Agent 쪽 프롬프트나 도구 목록을 arm마다
 * 바꾸면 달라진 변수가 "지도 가용성" 하나가 아니게 된다. 서버에서 막으면 arm마다 Agent 프롬프트가
 * 바이트 단위로 동일하고, 남는 차이는 조회 응답이 비어 있다는 것뿐이다. agent-server 쪽은 이미
 * 그것을 견딘다 — `app/qa/scene_context.py` 의 `SceneContext.render` 가 `knownToContentMap=false`
 * 인 씬을 "지도가 이 씬을 들어 본 적 없다" 로 그리고, 항목이 아예 없는 씬은 블록 자체를 안 그린다.
 *
 * 저장은 소문자 wire 토큰(`on`/`frozen`/`off`)이다. `run_config` 는 Agent 가 준 JSON 과 한 객체를
 * 이루므로 그쪽 표기(snake_case 소문자)를 따른다.
 */
enum class ContentMapMode(val wire: String) {
    ON("on"),
    FROZEN("frozen"),
    OFF("off");

    /** 이 모드에서 조회가 실제로 지도를 읽는가. */
    val readable: Boolean get() = this != OFF

    /** 이 모드에서 런이 지도에 쓸 수 있는가. */
    val writable: Boolean get() = this == ON

    companion object {
        /** `run_config`에 값이 없는 런(이 변경 이전 런 포함)은 지금까지처럼 읽고 쓴다. */
        val DEFAULT = ON

        val WIRE_NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.wire }

        fun fromWire(value: String?): ContentMapMode? =
            value?.trim()?.lowercase()?.let { normalized -> entries.firstOrNull { it.wire == normalized } }
    }
}
