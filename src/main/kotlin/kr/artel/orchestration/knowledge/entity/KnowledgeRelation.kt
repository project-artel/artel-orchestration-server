package kr.artel.orchestration.knowledge.entity

/**
 * knowledge 항목 둘 사이의 관계(ARTEL-274). 저장은 이름 그대로 VARCHAR + CHECK이고,
 * [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시 매칭) — [KnowledgeTag]와 같은 모양이다.
 *
 * **각 값이 통과해야 하는 시험은 "읽는 쪽이 그것 때문에 다르게 행동하는가"다.** 그 시험을 통과하지
 * 못하는 값은 분류만 늘리고 판단은 못 바꾸며, 에이전트에게는 고를 것이 하나 더 생길 뿐이다.
 *
 * - [LEADS_TO]    : `from` 화면에서 `to` 화면으로 가는 경로가 있다. **화면 지도**의 전부다.
 *                   거의 모든 QA 런이 무언가를 시험하기 전에 거기 도달하는 법부터 알아내는 데
 *                   시간을 쓰고, 지금은 매 런이 그것을 맨바닥에서 다시 한다.
 *                   note가 예외적으로 "왜"가 아니라 **무엇을 했는지**를 진다.
 * - [CONTRADICTS] : 둘이 동시에 참일 수 없다. 가장 값진 신호다 — 판정을 바꾸고(둘 다 맹신하면
 *                   안 된다) 운영자에게는 지식창고가 모순이 됐다는 경보다. **대칭**이다.
 * - [REFINES]     : `from`이 `to`의 더 좁은 경우·예외·조건. 일반 규칙에 걸렸을 때 그 예외가
 *                   딸려 오는 것 — 의미 관계 쪽의 주력이다.
 * - [DEPENDS_ON]  : `from`은 `to`가 성립하는 동안만 성립한다(선행조건). REFINES와 행동이 다르다 —
 *                   `from`을 쓰기 **전에** 선행조건이 지금 성립하는지 확인해야 한다.
 * - [REPLACES]    : `from`이 `to`를 대체한다. 유일한 생명주기 값이고, V27이 컬럼만 만들어 두고
 *                   한 번도 채우지 않은 `replaces_id`의 자리를 가져간다(V29에서 그 컬럼은 드롭).
 *
 * **거부한 후보와 이유.**
 * - `RELATED_TO` / `SEE_ALSO` — 벡터 채널이 이미 "이것과 비슷한 게 또 뭐냐"에 답하고, 그쪽은
 *   계산값이라 오염되지 않는다. 더 나쁜 것은 catch-all이 **기본값이 된다**는 것이다: 쉬운 선택
 *   하나와 어려운 넷이 있으면 쉬운 것이 골라지고, 그래프는 무타입으로 퇴화한다. 도구 설명이
 *   대신 "다섯 중 맞는 것이 없으면 링크하지 말라"고 말한다.
 * - `PART_OF` / `SUBSUMES` — [REFINES]와 거의 겹친다. 두 런이 같은 쌍을 둘로 쪼개고, 읽는 쪽은
 *   어차피 똑같이 다룬다.
 * - `CAUSES` — 게임 메커니즘에 대한 주장이라 항목의 `description`에 속한다.
 * - `SAME_AS` / `DUPLICATE_OF` — 중복은 병합할 것이지 영구화할 것이 아니다.
 * - `SUPERSEDED_BY` — [REPLACES]를 거꾸로 읽은 것. 방향 하나로 한 번만 저장한다.
 *
 * `SIMILAR`은 여기 **없다.** 벡터 이웃은 조회 시점 계산값이고 표시용 라벨만 그 이름을 쓴다.
 * enum에 넣으면 CHECK를 통과해 저장될 수 있게 되는데, 저장된 유사도는 임베딩 모델이 바뀌는
 * 순간 조용히 거짓이 된다.
 */
enum class KnowledgeRelation {
    LEADS_TO,
    CONTRADICTS,
    REFINES,
    DEPENDS_ON,
    REPLACES;

    /**
     * 방향이 없는 관계인가. 지금은 [CONTRADICTS]뿐이다.
     *
     * 대칭 관계는 **한 행**으로 저장하고 양방향으로 조회한다. 두 행으로 두면 쓰기가 두 배가 되고,
     * unlink가 반쯤 실패할 수 있는 2행 연산이 되며, `uq_knowledge_edge_live`가 (A,B)와 (B,A)를
     * 같은 주장으로 못 본다. 대신 쓰기 시점에 `from = min, to = max`로 정규화한다([normalize]).
     */
    val symmetric: Boolean get() = this == CONTRADICTS

    /**
     * 이 관계의 [from]/[to]를 저장할 순서로 맞춘다.
     *
     * 대칭 관계만 정렬되고 나머지는 그대로다. DB의 `ck_knowledge_edge_symmetric_order`가 같은
     * 불변식을 걸고 있으므로, 이 함수를 빠뜨린 쓰기는 저장에 실패한다 — 조용히 두 방향이
     * 따로 쌓이는 것보다 낫다.
     */
    fun normalize(from: Long, to: Long): Pair<Long, Long> =
        if (symmetric && from > to) to to from else from to to

    /**
     * fanout 상한에 걸렸을 때 어느 이웃을 남길지의 우선순위(작을수록 먼저).
     *
     * [CONTRADICTS]가 맨 앞인 것은 그것이 **판정을 바꾸는 유일한 신호**이기 때문이다. 나머지는
     * 맥락을 더할 뿐이지만 모순은 "둘 다 믿지 말라"고 말한다. [LEADS_TO]가 그다음인 것은 그
     * 화면에 서 있는 순간 가장 실행 가능한 정보이기 때문이다.
     *
     * 순서 자체는 아직 추측이다 — 실제 그래프가 생긴 뒤 재검토한다. 특히 허브 화면에서
     * [LEADS_TO]가 같은 노드의 [REFINES]를 밀어낼 수 있다.
     */
    val traversalPriority: Int get() = ordinal

    companion object {
        val NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        fun fromWire(value: String?): KnowledgeRelation? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}
