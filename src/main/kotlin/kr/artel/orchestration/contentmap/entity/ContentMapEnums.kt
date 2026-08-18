package kr.artel.orchestration.contentmap.entity

/**
 * content_map 이 쓰는 열거값. DB 는 전부 `VARCHAR` + `CHECK` 로 두고 여기서 문자열을 준다.
 *
 * R2DBC 는 JPA 의 `@Enumerated` 같은 자동 변환이 없어 컨버터를 붙이거나 문자열로 오간다.
 * 이 도메인은 값이 SQL 뷰([kr.artel.orchestration.contentmap] 의 `v_spec_gap`)와 적재기 양쪽에
 * 문자열로 등장하므로, 엔티티도 문자열을 들고 이 상수로 대조한다 — 한쪽만 enum 으로 바꾸면
 * 뷰의 리터럴과 코드의 상수가 조용히 어긋난다.
 */
object Capture {
    /** 아무것도 실행되지 않은 씬을 읽은 것. 값은 authoring 값이다. */
    const val EDITOR = "editor"
    const val EDITOR_PLAY = "editor-play"

    /** 플레이가 지나간 뒤의 값. 같은 필드가 [EDITOR] 와 다른 뜻이다. */
    const val PLAYER = "player"

    val ALL = setOf(EDITOR, EDITOR_PLAY, PLAYER)
}

/**
 * 이 기능을 어디서 알아냈나.
 *
 * [VERIFICATION][VerificationState] 과 **다른 축이다.** 이쪽은 출처이고 저쪽은 실행 확인이다.
 * 하나로 뭉치면 "IL 분석기가 확신함"과 "돌려봐서 됨"을 구분하지 못한다.
 */
object CapabilityOrigin {
    /** IL 근거 + 씬 순회가 증명. `capability_evidence` 행을 반드시 갖는다. */
    const val EVIDENCE = "evidence"

    /** QA 런이 눌러보니 되더라. 근거에 없던 기능이 여기로 들어온다. */
    const val OBSERVED = "observed"

    /** agent 가 관측에서 추론. `capability_inference` 행을 반드시 갖는다. */
    const val INFERRED = "inferred"

    const val HUMAN = "human"

    val ALL = setOf(EVIDENCE, OBSERVED, INFERRED, HUMAN)
}

/**
 * 실행으로 확인됐나.
 *
 * [CONTRADICTED] 를 지우지 않는 이유: 지우면 "우리가 틀렸다"와 "게임이 고장났다"를 구분할 기록이
 * 사라진다. 결함 리포트의 재료이자 우리 정적 분석이 틀렸다는 신호이기도 하다.
 */
object VerificationState {
    const val UNVERIFIED = "unverified"
    const val CONFIRMED = "confirmed"
    const val CONTRADICTED = "contradicted"

    val ALL = setOf(UNVERIFIED, CONFIRMED, CONTRADICTED)
}

/**
 * 무엇을 하는 조작인가. **프로토콜 메서드가 아니라 의도다.**
 *
 * `button_click` 같은 이름은 SDK 의 것이고 배포마다 바뀐다. 판독의 `offers` 가 그 오브젝트가 지금
 * 무엇에 응답하는지 실어 주므로 실제 메서드는 agent 가 런타임에 정한다.
 */
object Interaction {
    const val CLICK = "click"
    const val TYPE = "type"
    const val PRESS = "press"
    const val AXIS = "axis"

    /** 조작 없이 일어나는 것 — 타이머·로딩 완료·코루틴. TC 가 지시할 수 없다. */
    const val NONE = "none"

    val ALL = setOf(CLICK, TYPE, PRESS, AXIS, NONE)
}

object InputPhase {
    const val DOWN = "down"
    const val HELD = "held"
    const val UP = "up"

    val ALL = setOf(DOWN, HELD, UP)
}

/**
 * TC 로 만들 수 있는가.
 *
 * [NEEDS_PROBE] 는 실패가 아니다. 조작은 지시할 수 있고 기대 결과만 모르는 것이라, 1회차 QA 런이
 * 관측을 기록하면 2회차부터 그 관측이 기대 결과가 된다.
 */
object SpecStatus {
    /** 조작이 있고 관측 가능한 효과가 하나 이상. 판정까지 자동. */
    const val RUNNABLE = "runnable"

    /** 조작은 있는데 효과가 전부 내부 상태. 무엇이 달라지는지는 pulse 가 말한다. */
    const val NEEDS_PROBE = "needs-probe"

    /** 조건은 아는데 그 상태를 만드는 절차가 근거에 없다. */
    const val UNREACHABLE_PRECONDITION = "unreachable-precondition"

    /** 조작이 없다. 단독 명세가 아니라 given/then 의 재료로만 쓴다. */
    const val NOT_A_STEP = "not-a-step"

    val ALL = setOf(RUNNABLE, NEEDS_PROBE, UNREACHABLE_PRECONDITION, NOT_A_STEP)
}

/**
 * 효과가 명세의 `then` 이 될 수 있는가.
 *
 * [STATE] 는 결과로 쓰지 않는다 — 이름으로 화면 변화를 짐작하면 조용히 틀린다. 실측에서 47건이
 * 이름 기반 오분류였다(`MapMove.position` 은 화면 좌표가 아니라 레인 인덱스였다).
 */
object EffectCategory {
    const val OBSERVABLE = "observable"
    const val AVAILABILITY = "availability"
    const val STATE = "state"

    val ALL = setOf(OBSERVABLE, AVAILABILITY, STATE)

    /** `then` 에 그대로 쓸 수 있는 것. */
    val ASSERTABLE = setOf(OBSERVABLE, AVAILABILITY)
}

/** 효과를 근거가 말했나, 관측이 말했나. 관측에만 있는 것이 근거의 구멍이다. */
object EffectOrigin {
    const val EVIDENCE = "evidence"
    const val OBSERVED = "observed"

    val ALL = setOf(EVIDENCE, OBSERVED)
}

/** 근거 레코드가 명세 후보인가 연결점인가. */
object RecordKind {
    const val CANDIDATE = "candidate"
    const val FLOW = "flow"

    val ALL = setOf(CANDIDATE, FLOW)
}

/**
 * 근거가 이 진입점을 어떻게 분류했나.
 *
 * [UNITY_EVENT] 는 "인스펙터가 부를 수 있는 모양"이지 **배선됐다는 뜻이 아니다.** 실측에서
 * `unity-event` 가 106건인데 실제 배선은 7쌍이었다. 배선 여부는 씬 쪽 `objects[].calls` 만 안다.
 */
object TriggerKind {
    const val UNITY_EVENT = "unity-event"
    const val LIFECYCLE = "lifecycle"

    val ALL = setOf(UNITY_EVENT, LIFECYCLE)
}

/**
 * IL 분석기의 자기 확신도. **실행 확인이 아니다** — 그것은 [VerificationState] 다.
 * 이름이 겹쳐 혼동되던 자리라 서브테이블로 내려두었다.
 */
object AnalysisConfidence {
    const val VERIFIED = "verified"
    const val DERIVED = "derived"
    const val PARTIAL = "partial"

    val ALL = setOf(VERIFIED, DERIVED, PARTIAL)
}

/**
 * 씬 전이를 어떻게 알았나.
 *
 * [STATIC] 이 먼저 있고 QA 런이 `verified_at` 을 찍는다. [RUNTIME] 은 정적 분석이 놓친 전이다.
 */
object EdgeSource {
    const val STATIC = "static"
    const val RUNTIME = "runtime"

    val ALL = setOf(STATIC, RUNTIME)
}

/** 화면 전이가 무엇 때문에 일어났나. `auto` 는 TC 가 지시할 수 없다. */
object TransitionKind {
    const val ACTION = "action"
    const val STATE = "state"
    const val AUTO = "auto"

    val ALL = setOf(ACTION, STATE, AUTO)
}

/**
 * 명세가 못 된 이유. `v_spec_gap` 이 내는 값과 같아야 한다.
 *
 * QA 결함이 아니라 **개발 우선순위 신호**다. [THEN_MISSING] 이 많으면 수집기(SDK)를 고칠 차례이고,
 * [GIVEN_SUBJECT_UNKNOWN] 이 많으면 조건 분석기의 주어 추적이 약한 것이다.
 */
object SpecGapReason {
    const val WHEN_MISSING = "when-missing"
    const val GIVEN_SUBJECT_UNKNOWN = "given-subject-unknown"
    const val GIVEN_INCOMPLETE = "given-incomplete"
    const val GIVEN_UNREAD = "given-unread"
    const val THEN_MISSING = "then-missing"
    const val THEN_DETAIL_UNKNOWN = "then-detail-unknown"

    val ALL = setOf(
        WHEN_MISSING,
        GIVEN_SUBJECT_UNKNOWN,
        GIVEN_INCOMPLETE,
        GIVEN_UNREAD,
        THEN_MISSING,
        THEN_DETAIL_UNKNOWN,
    )
}
