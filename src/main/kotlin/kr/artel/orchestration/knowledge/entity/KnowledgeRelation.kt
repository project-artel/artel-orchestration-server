package kr.artel.orchestration.knowledge.entity

/**
 * knowledge 항목 둘 사이의 관계(ARTEL-274). 저장은 이름 그대로 VARCHAR + CHECK이고,
 * [NAMES]는 인바운드 값 검증에 쓴다(대소문자 무시 매칭) — [KnowledgeTag]와 같은 모양이다.
 *
 * **각 값이 통과해야 하는 시험은 "읽는 쪽이 그것 때문에 다르게 행동하는가"다.** 그 시험을 통과하지
 * 못하는 값은 분류만 늘리고 판단은 못 바꾸며, 에이전트에게는 고를 것이 하나 더 생길 뿐이다.
 *
 * - [LEADS_TO]    : `from` 화면에서 `to` 화면으로 가는 경로가 있다. 한때 **화면 지도**의 전부였고,
 *                   거의 모든 QA 런이 무언가를 시험하기 전에 거기 도달하는 법부터 알아내는 데
 *                   시간을 쓴다는 것이 그 근거였다. note가 예외적으로 "왜"가 아니라 **무엇을
 *                   했는지**를 진다. **지금은 쓰기가 얼어 있다** — 아래 "얼린 값" 절을 볼 것.
 * - [CONTRADICTS] : 둘이 동시에 참일 수 없다. 가장 값진 신호다 — 판정을 바꾸고(둘 다 맹신하면
 *                   안 된다) 운영자에게는 지식창고가 모순이 됐다는 경보다. **대칭**이다.
 * - [REFINES]     : `from`이 `to`의 더 좁은 경우·예외·조건. 일반 규칙에 걸렸을 때 그 예외가
 *                   딸려 오는 것 — 의미 관계 쪽의 주력이다.
 * - [DEPENDS_ON]  : `from`은 `to`가 성립하는 동안만 성립한다(선행조건). REFINES와 행동이 다르다 —
 *                   `from`을 쓰기 **전에** 선행조건이 지금 성립하는지 확인해야 한다.
 * - [REPLACES]    : `from`이 `to`를 대체한다. 유일한 생명주기 값이고, V27이 컬럼만 만들어 두고
 *                   한 번도 채우지 않은 `replaces_id`의 자리를 가져간다(V29에서 그 컬럼은 드롭).
 *
 * **얼린 값 — [LEADS_TO]는 읽기만 된다(ARTEL-594).**
 *
 * 화면 지도의 소유가 `content_map`으로 넘어갔다. 경로는 이제 `screen_transition`과 `scene_edge`가
 * 지고, 그쪽은 실제 플레이 관측이 근거라 런이 손으로 적어 넣은 주장보다 강하다. 그러면 같은 것을
 * 말하는 지도가 둘인데, **둘 다 쓰기를 받으면 영원히 갈라진다** — 어긋났을 때 어느 쪽이 맞는지
 * 판정할 근거가 어디에도 없기 때문이다. 그래서 지식창고 쪽 사본을 얼렸다:
 * [kr.artel.orchestration.knowledge.service.KnowledgeGraphService]의 link와 unlink가 이 값을
 * 받으면 사유를 담아 거절하고, 이미 저장된 행은 검색·이웃·확장·그래프 조회에 그대로 나온다.
 * agent 쪽도 같은 규칙이다(ARTEL-590) — 쓰기 어휘에서 빠져 도구가 링크도 언링크도 못 보낸다.
 *
 * **enum에서 지우지 않고 CHECK도 좁히지 않는다.** 지우면 저장된 행이 역직렬화되지 않고, CHECK를
 * 좁히면 그 행들이 제약을 통과하지 못한다. 얼리는 것은 어휘가 아니라 **쓰기 경로**의 일이라
 * 거절은 서비스 층에 산다. 이 값은 [NAMES]에도 그대로 남는다 — 빠지면 인바운드 검증이
 * `LEADS_TO`를 "모르는 이름"이라고 답하게 되는데, 아는 이름이고 쓰기만 막힌 것이다.
 *
 * 언링크까지 막는 데는 대가가 있다: 잘못 저장된 경로 간선을 이 경로로는 떼어낼 수 없다. 받아들이는
 * 이유는 남은 간선이 더는 지식창고의 지도가 아니라 **과거 런이 알아낸 것의 기록**으로만 읽히기
 * 때문이고, 정말 지워야 하면 사람이 일회성으로 정리한다. 반대로 언링크만 열어 두면 서버와 agent가
 * 서로 다른 규칙을 말하게 되어, 다음에 둘 중 하나를 읽는 사람이 속는다.
 *
 * **거부한 후보와 이유.**
 * - `RELATED_TO` / `SEE_ALSO` — 벡터 채널이 이미 "이것과 비슷한 게 또 뭐냐"에 답하고, 그쪽은
 *   계산값이라 오염되지 않는다. 더 나쁜 것은 catch-all이 **기본값이 된다**는 것이다: 쉬운 선택
 *   하나와 어려운 넷이 있으면 쉬운 것이 골라지고, 그래프는 무타입으로 퇴화한다. 도구 설명이
 *   대신 "다섯 중 맞는 것이 없으면 링크하지 말라"고 말한다.
 * - `PART_OF` / `SUBSUMES` — [REFINES]와 거의 겹친다. 두 런이 같은 쌍을 둘로 쪼개고, 읽는 쪽은
 *   어차피 똑같이 다룬다. **이 거부는 QA agent가 `KNOWLEDGE_LINK`로 손수 주장하는 관계 어휘에
 *   대한 것이다.** ARTEL-748에서 `PART_OF`가 실제로 저장되기 시작했지만, 그것은 이 어휘가 아니라
 *   문서 적재 파이프라인([kr.artel.orchestration.knowledge.service.KnowledgeService.store])만
 *   쓰는 구조적 관계다("항목이 어느 문서에서 왔는가") — REFINES가 지는 의미 관계와 다른 축이라
 *   위 거부 사유와 충돌하지 않는다. 그래서 이 enum에는 여전히 없고 [fromWire]도 이 값을 모른다:
 *   넣으면 `KnowledgeGraphService`의 link/unlink가 이 값을 파싱하게 되어 agent가 그 경로로
 *   `PART_OF`를 만들거나 거둘 길이 열리고, 그러면 [LEADS_TO]처럼 서비스 층에서 다시 얼려야 한다.
 *   그 문자열은 [PART_OF_RELATION] 상수 하나에서만 온다.
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

/**
 * `knowledge_edge.relation`의 구조적 값(ARTEL-748). [KnowledgeRelation]의 agent 어휘에 없는 이유는
 * 그 enum의 "거부한 후보와 이유" 절에 있다 — 이 값을 만드는 코드는
 * [kr.artel.orchestration.knowledge.service.KnowledgeService.store] 하나뿐이고, agent가 손으로
 * 주장할 수 없어야 한다.
 *
 * top-level에 둔 이유는 이 값을 찾는 사람이 보는 자리가 관계 이름들이 모인 이 파일이기 때문이다.
 */
const val PART_OF_RELATION = "PART_OF"
