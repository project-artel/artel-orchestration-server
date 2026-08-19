package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord

/**
 * 조인 단계들이 주고받는 값. 단계마다 자기 모양을 따로 만들면 같은 뜻의 값이 세 벌이 된다.
 *
 * 이 파일에는 **데이터만** 둔다. 계산은 각 단계가 자기 파일에서 한다.
 */

/** 어떤 타입이 놓인 자리 하나. */
data class ScenePlacement(
    val scene: String,
    /** 사람이 읽는 계층 경로. `Canvas/continue`. */
    val path: String,
    /** 형제 인덱스가 붙은 조준용 경로. 문서가 `selector-v1` 을 약속하지 않으면 null 이다. */
    val selector: String?,
    /** 그 오브젝트에 쓰인 글자. 없으면 null. */
    val label: String?,
)

/** 배선을 찾은 길. 어느 길로 걸렸는지가 곧 신뢰도라 값에 남긴다. */
enum class WiringPath {
    /** 컨트롤이 부르는 (타입, 메서드) 가 레코드의 진입점과 바로 맞았다. */
    ENTRY,

    /** `alsoReachedBy` 를 펴야 맞았다. `DuplicateVariants` 가 접어 둔 둘째 길이다. */
    ARRIVAL,

    /** 코드가 런타임에 `AddListener` 로 매단 것이라 인스펙터 배선에는 없다. */
    HANDLE,
}

/** "이 컨트롤을 이렇게 하면 저 메서드가 불린다" — 씬 쪽 절반. */
data class ControlBinding(
    val placement: ScenePlacement,
    /** `m_OnClick` 등. [WiringPath.HANDLE] 이면 `handles[].channel` 에서 온다. */
    val event: String?,
    val via: WiringPath,
)

/**
 * 프리팹 위에만 사는 타입이 씬에 붙는 자리.
 *
 * [field] 가 null 이면 후보가 둘 이상이라 정하지 않은 것이다 — 그때 [ambiguousCandidates] 가 찬다.
 */
data class SpawnOrigin(
    val scene: String,
    val field: String?,
    /** `refs[].carries` 가 씬 경로까지 줬을 때만. 대개 null 이다. */
    val scenePath: String?,
    val ambiguousCandidates: List<String> = emptyList(),
) {
    // `field` 만 쓰면 접근자 안에서는 뒷받침 필드 키워드로 읽혀 컴파일되지 않는다.
    val ambiguous: Boolean get() = this.field == null
}

/**
 * 조인이 만든 기능 후보 하나. 적재기(ARTEL-442)가 이것을 행으로 바꾼다.
 *
 * `capability` 행과 1:1 이지만 DB 어휘를 쓰지 않는다 — 여기는 아직 "무엇을 알아냈나"이고, 축과 키는
 * 적재가 정한다.
 */
data class CapabilityCandidate(
    val scene: String,
    /** 이 후보를 낳은 근거 레코드. 되짚기의 출처다. */
    val record: EvidenceRecord,
    /** 배선으로 찾았으면 그 컨트롤. 스폰으로 붙었으면 null. */
    val binding: ControlBinding?,
    /** 스폰으로 붙었으면 그 출처. 배선으로 찾았으면 null. */
    val spawn: SpawnOrigin?,
    /** `click` · `press` · `none` 등. 액션 프로토콜 어휘가 아니라 의도다. */
    val interaction: String,
    /** [interaction] 이 `press` 일 때의 키 이름. `either` 를 쪼갠 뒤라 항상 단일 값이다. */
    val inputKey: String? = null,
    val inputPhase: String? = null,
    /**
     * 이 후보만 남긴 조건 갈래.
     *
     * `either` 를 쪼개면 갈래마다 다른 트리가 되고, 씬 이름 조건으로 거르면 또 달라진다. 원본
     * 전체는 [EvidenceRecord.conditionJson] 이 계속 들고 있다.
     */
    val condition: ConditionNode,
    /** 이 갈래를 가른 IL 위치. 한 메서드 안의 서로 다른 지점을 구분한다. */
    val branchOffset: Int?,
    /**
     * 문서의 확신도를 스키마 어휘로 옮긴 값.
     *
     * 여기서 옮겨 두지 않으면 적재기가 `verified` 같은 문서 문자열을 다시 비교하게 되고, 어휘가 두
     * 곳에서 갈라진다. 옮기는 규칙과 근거는 [RecordTranslation.confidenceOf] 에 있다.
     */
    val confidence: AnalysisConfidence,
    /** 근거가 말한 공백 + 조인이 판정한 공백. `subject-null` · `spawn-origin-ambiguous` 등. */
    val gaps: List<String>,
)
