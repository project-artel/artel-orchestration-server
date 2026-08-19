package kr.artel.orchestration.contentmap.entity

/**
 * content_map 이 쓰는 열거값.
 *
 * DB 는 `VARCHAR` + `CHECK` 로 두고 엔티티는 문자열을 든다 — R2DBC 는 JPA 의 `@Enumerated` 가 없고,
 * 이 값들은 SQL 뷰(`v_spec_gap`)에도 리터럴로 등장한다. 코드 쪽에서 문자열을 직접 쓰지 않도록
 * [wire] 로 그 문자열을 한 군데 모으고, [from] 으로 되돌린다. `KnowledgeMode` 와 같은 모양이다.
 */
enum class Capture(val wire: String) {
    /** 아무것도 실행되지 않은 씬을 읽은 것. 값은 authoring 값이다. */
    EDITOR("editor"),
    EDITOR_PLAY("editor-play"),

    /** 플레이가 지나간 뒤의 값. 같은 필드가 [EDITOR] 와 다른 뜻이다. */
    PLAYER("player");

    companion object {
        fun from(wire: String?): Capture? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 이 기능을 어디서 알아냈나.
 *
 * [VerificationState] 와 **다른 축이다.** 이쪽은 출처이고 저쪽은 실행 확인이다. 하나로 뭉치면
 * "IL 분석기가 확신함"과 "돌려봐서 됨"을 구분하지 못한다.
 */
enum class CapabilityOrigin(val wire: String) {
    /** IL 근거 + 씬 순회가 증명. `capability_evidence` 행을 반드시 갖는다(복합 FK 가 강제). */
    EVIDENCE("evidence"),

    /** QA 런이 눌러보니 되더라. 근거에 없던 기능이 여기로 들어온다. */
    OBSERVED("observed"),

    /** agent 가 관측에서 추론. `capability_inference` 행이 딛고 선 관측을 밝힌다. */
    INFERRED("inferred"),

    HUMAN("human");

    companion object {
        fun from(wire: String?): CapabilityOrigin? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 실행으로 확인됐나.
 *
 * [CONTRADICTED] 를 지우지 않는 이유: 지우면 "우리가 틀렸다"와 "게임이 고장났다"를 구분할 기록이
 * 사라진다. 결함 리포트의 재료이자 우리 정적 분석이 틀렸다는 신호이기도 하다.
 */
enum class VerificationState(val wire: String) {
    UNVERIFIED("unverified"),
    CONFIRMED("confirmed"),
    CONTRADICTED("contradicted");

    companion object {
        fun from(wire: String?): VerificationState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 무엇을 하는 조작인가. **프로토콜 메서드가 아니라 의도다.**
 *
 * `button_click` 같은 이름은 SDK 의 것이고 배포마다 바뀐다. 판독의 `offers` 가 그 오브젝트가 지금
 * 무엇에 응답하는지 실어 주므로 실제 메서드는 agent 가 런타임에 정한다.
 */
enum class Interaction(val wire: String) {
    CLICK("click"),
    TYPE("type"),
    PRESS("press"),
    AXIS("axis"),

    /** 조작 없이 일어나는 것 — 타이머·로딩 완료·코루틴. TC 가 지시할 수 없다. */
    NONE("none");

    companion object {
        fun from(wire: String?): Interaction? = entries.firstOrNull { it.wire == wire }

        /**
         * "아무 키" 조작의 `input_key` 표기.
         *
         * `ck_capability_press_needs_key` 가 `press` 에 키를 요구하는데, 근거는 특정 키를 지목하지
         * 않는다("대사가 끝날 때까지 아무 키를 누른다"). 실제 키 하나를 골라 적으면 거짓 명세가
         * 되므로 sentinel 을 둔다.
         *
         * **키 이름이 아니다.** 실행 에이전트는 이 값을 보면 아무 키나 골라 보낸다.
         */
        const val ANY_INPUT_KEY: String = "any"
    }
}

enum class InputPhase(val wire: String) {
    DOWN("down"),
    HELD("held"),
    UP("up");

    companion object {
        fun from(wire: String?): InputPhase? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * TC 로 만들 수 있는가.
 *
 * [NEEDS_PROBE] 는 실패가 아니다. 조작은 지시할 수 있고 기대 결과만 모르는 것이라, 1회차 QA 런이
 * 관측을 기록하면 2회차부터 그 관측이 기대 결과가 된다.
 */
enum class SpecStatus(val wire: String) {
    /** 조작이 있고 관측 가능한 효과가 하나 이상. 판정까지 자동. */
    RUNNABLE("runnable"),

    /** 조작은 있는데 효과가 전부 내부 상태. 무엇이 달라지는지는 pulse 가 말한다. */
    NEEDS_PROBE("needs-probe"),

    /** 조건은 아는데 그 상태를 만드는 절차가 근거에 없다. */
    UNREACHABLE_PRECONDITION("unreachable-precondition"),

    /** 조작이 없다. 단독 명세가 아니라 given/then 의 재료로만 쓴다. */
    NOT_A_STEP("not-a-step");

    companion object {
        fun from(wire: String?): SpecStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 효과가 명세의 `then` 이 될 수 있는가.
 *
 * [STATE] 는 결과로 쓰지 않는다 — 이름으로 화면 변화를 짐작하면 조용히 틀린다. 실측에서 47건이
 * 이름 기반 오분류였다(`MapMove.position` 은 화면 좌표가 아니라 레인 인덱스였다).
 */
enum class EffectCategory(val wire: String) {
    OBSERVABLE("observable"),
    AVAILABILITY("availability"),
    STATE("state");

    /** `then` 에 그대로 쓸 수 있는가. */
    val assertable: Boolean get() = this != STATE

    companion object {
        fun from(wire: String?): EffectCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** 효과를 근거가 말했나, 관측이 말했나. 관측에만 있는 것이 근거의 구멍이다. */
enum class EffectOrigin(val wire: String) {
    EVIDENCE("evidence"),
    OBSERVED("observed");

    companion object {
        fun from(wire: String?): EffectOrigin? = entries.firstOrNull { it.wire == wire }
    }
}

/** 근거 레코드가 명세 후보인가 연결점인가. */
enum class RecordKind(val wire: String) {
    CANDIDATE("candidate"),
    FLOW("flow");

    companion object {
        fun from(wire: String?): RecordKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 근거가 이 진입점을 어떻게 분류했나.
 *
 * [UNITY_EVENT] 는 "인스펙터가 부를 수 있는 모양"이지 **배선됐다는 뜻이 아니다.** 실측에서
 * `unity-event` 가 106건인데 실제 배선은 7쌍이었다. 배선 여부는 씬 쪽만 안다.
 */
enum class TriggerKind(val wire: String) {
    UNITY_EVENT("unity-event"),
    LIFECYCLE("lifecycle");

    companion object {
        fun from(wire: String?): TriggerKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * IL 분석기의 자기 확신도. **실행 확인이 아니다** — 그것은 [VerificationState] 다.
 * 이름이 겹쳐 혼동되던 자리라 서브테이블로 내려두었다.
 */
enum class AnalysisConfidence(val wire: String) {
    VERIFIED("verified"),
    DERIVED("derived"),
    PARTIAL("partial");

    companion object {
        fun from(wire: String?): AnalysisConfidence? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 씬 전이를 어떻게 알았나.
 *
 * [STATIC] 이 먼저 있고 QA 런이 `verified_at` 을 찍는다. [RUNTIME] 은 정적 분석이 놓친 전이다.
 */
enum class EdgeSource(val wire: String) {
    STATIC("static"),
    RUNTIME("runtime");

    companion object {
        fun from(wire: String?): EdgeSource? = entries.firstOrNull { it.wire == wire }
    }
}

/** 화면 전이가 무엇 때문에 일어났나. [AUTO] 는 TC 가 지시할 수 없다. */
enum class TransitionKind(val wire: String) {
    ACTION("action"),
    STATE("state"),
    AUTO("auto");

    companion object {
        fun from(wire: String?): TransitionKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 명세가 못 된 이유. `v_spec_gap` 이 내는 값과 정확히 같아야 한다.
 *
 * QA 결함이 아니라 **개발 우선순위 신호**다. [THEN_MISSING] 이 많으면 수집기(SDK)를 고칠 차례이고,
 * [GIVEN_SUBJECT_UNKNOWN] 이 많으면 조건 분석기의 주어 추적이 약한 것이다.
 *
 * [EVIDENCE_MISSING] 만 성격이 다르다 — 게임의 근거가 부족한 것이 아니라 **우리 적재기가 근거를
 * 잃은 것**이라, 이것이 세어지면 고칠 곳은 SDK 가 아니라 우리 쪽이다.
 */
enum class SpecGapReason(val wire: String) {
    EVIDENCE_MISSING("evidence-missing"),
    WHEN_MISSING("when-missing"),
    GIVEN_SUBJECT_UNKNOWN("given-subject-unknown"),
    GIVEN_INCOMPLETE("given-incomplete"),
    GIVEN_UNREAD("given-unread"),
    THEN_MISSING("then-missing"),
    THEN_DETAIL_UNKNOWN("then-detail-unknown");

    companion object {
        fun from(wire: String?): SpecGapReason? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `capability_evidence.gaps` 에 담기는 **입력** 토큰. 근거 문서가 말한 것과 적재기가 판정한 것.
 *
 * [SpecGapReason] 과 다르다 — 이쪽이 입력이고 저쪽이 출력이다. `v_spec_gap` 이 매칭하는 문자열이
 * 여기 있으므로, 적재기가 출력 이름을 넣으면 분기가 영영 걸리지 않는다.
 */
enum class EvidenceGap(val wire: String) {
    /** 조건의 주어를 못 정했다(`context: null`). 여기서 주어를 상상하면 거짓 명세가 된다. */
    SUBJECT_NULL("subject-null"),

    /** 호출된 쪽 조건을 못 합쳤다. given 에 빠진 것이 있다는 뜻이다. */
    CALLEE_CONDITION_NOT_COMPOSED("callee-condition-not-composed"),

    /** 조건을 읽지 못했다. 조건이 없는 게 아니라 모르는 것이다. */
    UNREAD_CONDITION("unread-condition"),

    /**
     * `method_id` 를 못 채웠다. 되짚기의 한쪽 축이 비었다는 뜻이다.
     *
     * DB 가 `method_id IS NULL` 인 행에 이 토큰을 요구한다 — 조용히 비면 "적재기가 못 채운 것"과
     * "근거에 원래 없는 것"이 구분되지 않는다.
     */
    METHOD_ID_MISSING("method-id-missing"),

    /** `call_path` 가 비었다. 어느 호출 사슬에서 나온 규칙인지 모른다는 뜻이다. */
    CALL_PATH_MISSING("call-path-missing");

    companion object {
        fun from(wire: String?): EvidenceGap? = entries.firstOrNull { it.wire == wire }
    }
}
