package kr.artel.orchestration.contentmap.evidence

/**
 * 근거 문서(schema 6)의 타입 있는 모델.
 *
 * 문서는 게임에서 뽑은 두 반쪽을 담는다 — [types] 는 코드가 아는 것, [objects] 는 씬이 아는 것이고
 * **둘은 서로를 모른다.** 조인이 그 둘을 잇고, 이 모델이 그 조인의 입력이다.
 *
 * `render/EvidenceDocument` 와 나뉜 이유: 저쪽은 렌더러가 원본 모양 그대로 찍어 내려고 `JsonNode` 를
 * 들고 다니는 판독기다. 조인은 값을 **계산**하므로 오타 난 키와 바뀐 타입이 파싱 경계에서 이름과 함께
 * 드러나야 한다(`coding-style.md` — 경계에서 한 번 파싱한다).
 */
data class EvidenceDocumentModel(
    val schema: Int,
    /** `editor` · `editor-play` · `player`. 같은 필드가 캡처마다 다른 뜻이라 키의 일부다. */
    val capture: String,
    /**
     * 문서가 하는 약속들(`build-info-v1` · `selector-v1` · `visual-roles-v1` · `persistent-objects-v1`).
     *
     * 원문 키는 `capabilities` 지만 그 이름은 이 도메인에서 기능을 뜻해, 읽는 쪽이 헷갈리지 않게 바꿔 담는다.
     * 필드 존재로 계약을 추론하면 안 되기에 따로 있다 — `selector-v1` 이 없으면 selector 를 믿으면 안 된다.
     */
    val promises: List<String>,
    val build: EvidenceBuild,
    val scenes: List<String>,
    /** 타입 풀네임 → 그 타입에서 뽑은 근거 레코드. 문서 순서를 보존한다(결정론). */
    val types: Map<String, List<EvidenceRecord>>,
    /** 아직 씬 오브젝트에 놓이지 못한 타입. 프리팹 위에만 사는 것들이 여기 있다. */
    val unplaced: Map<String, UnplacedType>,
    val objects: List<SceneObject>,
    /** `DontDestroyOnLoad` 로 씬을 넘어 사는 오브젝트. 배선은 [objects] 와 같은 모양이다. */
    val persistentObjects: List<SceneObject>,
    /** 문서 수준의 공백. 예: `dont-destroy-on-load-not-walked`. */
    val gaps: List<String>,
) {
    /** [objects] + [persistentObjects]. 배선을 찾을 때 둘을 가르지 않는다. */
    val allObjects: List<SceneObject> get() = objects + persistentObjects
}

data class EvidenceBuild(
    val unity: String?,
    val platform: String?,
    val backend: String?,
    val development: Boolean?,
    val sdk: String?,
    /** 구워진 근거 전체의 지문. 같은 capture 인데 값이 다르면 코드가 바뀐 것이다. */
    val digest: String?,
)

/**
 * 근거 레코드 하나 — "이 메서드의 이 지점에서 이런 일이 일어난다".
 *
 * 기능 하나가 아니다. 조인이 씬과 조작을 붙여야 기능 후보가 된다.
 */
data class EvidenceRecord(
    /**
     * 이 레코드가 매달린 타입. `types` 의 키와 같다.
     *
     * **[entryId] · [methodId] 의 타입과 다를 수 있다** — 실측 318건 중 entry 와 71건, method 와 15건이
     * 어긋난다(인라인된 callee, 다른 타입에서 시작한 진입점). 배치와 그룹핑은 이 값을 쓴다.
     */
    val owner: String,
    /** 진입점 메서드의 사람용 시그니처. */
    val entry: String,
    /** `Assembly-CSharp|타입|메서드|반환형(인자형)`. 난독화에도 살아남는 안정 키. */
    val entryId: String,
    /** 실제 동작이 있는 메서드의 사람용 시그니처. [entry] 와 다를 수 있다. */
    val source: String,
    /** [source] 의 안정 키. 다른 레코드의 `calls[].targetId` 가 이 값을 가리킨다. */
    val methodId: String,
    /** `candidate` — 조작 후보. `flow` — 흐름만. */
    val recordKind: String,
    /** `unity-event` — 인스펙터가 부를 수 있는 모양. `lifecycle` — Update 등. */
    val triggerKind: String,
    /**
     * 문서가 말하는 자기 확신도. **문서 어휘는 `verified` · `derived` · `partial` 이다** —
     * 스키마의 `analysis_confidence`(`exact` · `derived` · `ambiguous` · `unresolved`)와 다르다.
     * 번역은 후보 조립이 한다.
     */
    val confidence: String,
    /** 진입점부터 실제 동작까지의 호출 경로. 사람이 검증할 유일한 출처. */
    val callPath: List<String>,
    /** 타입 있는 조건 트리. gesture·씬 이름 조건을 찾는 데 쓴다. */
    val condition: ConditionNode,
    /**
     * 조건 트리 원본 JSON.
     *
     * `capability_evidence.condition_tree` 에는 **이 문자열이 그대로** 간다. 타입 트리에서 되쓰면
     * 우리 모델이 못 담은 키가 조용히 사라진다.
     */
    val conditionJson: String,
    val inputs: List<EvidenceInput>,
    val effects: List<EvidenceEffect>,
    val calls: List<EvidenceCall>,
    /** 런타임 `AddListener` 배선. 인스펙터 배선과 달리 코드 쪽에만 남는다. */
    val handles: List<EvidenceHandle>,
    /**
     * 같은 사실에 이르는 다른 길. `DuplicateVariants` 가 접은 것이라 **첫 길만 [entry] 에 있다.**
     *
     * 펴지 않으면 배선된 버튼이 통째로 사라진다 — 실측에서 `Canvas/continue` 가 그 경우다.
     */
    val alsoReachedBy: List<EvidenceArrival>,
    /** 문서가 "못 읽었다"고 말한 것. `callee-condition-not-composed` · `unread-condition` 등. */
    val gaps: List<String>,
    /** 이 메서드를 부르는 것들의 안정 키. 없으면 빈 목록. */
    val calledBy: List<String>,
    /** 되돌아가는 지점의 IL 위치. 반복해야 도달하는 조작의 근거다. */
    val loopsBackTo: Int? = null,
    /** 코루틴 등으로 제어를 넘긴 자리. */
    val handedOverTo: String? = null,
) {
    /**
     * 이 레코드에 이를 수 있는 모든 진입점의 안정 키 — [entryId] 와 [alsoReachedBy] 를 편 것.
     *
     * 배선 조인은 이 집합을 본다.
     */
    val arrivalEntryIds: List<String> get() = listOf(entryId) + alsoReachedBy.map { it.entryId }
}

/** `alsoReachedBy` 항목. `recordKind` 나 `confidence` 는 담기지 않는다 — 문서에 없다. */
data class EvidenceArrival(
    val entry: String,
    val entryId: String,
    val triggerKind: String,
    val callPath: List<String>,
)

/** 다른 메서드 호출. [targetId] 가 다른 레코드의 `methodId` 를 가리킨다. */
data class EvidenceCall(
    val targetId: String,
    val target: String,
    val receiver: String?,
    /** `this` · `static` · null. */
    val receiverWhere: String?,
    val args: String?,
    val offset: Int,
)

/**
 * 이 지점에서 읽은 입력.
 *
 * **조건 트리의 gesture 노드와 1:1 이 아니다** — 실측에서 마우스 입력 2건은 여기에만 있고 트리에는
 * `key:any` gesture 만 있었다(문서가 `input-not-branched` gap 을 남긴다).
 */
data class EvidenceInput(
    /** `key` · `mouse`. */
    val kind: String,
    /** 키 이름(`RightArrow` · `Return` · `any`) 또는 마우스 버튼 번호. */
    val control: String,
    /** `down` · `held` · `up`. 실측은 전부 `down`. */
    val phase: String,
    /** "눌리지 않았을 때"를 보는 조건이면 true. */
    val absent: Boolean,
    val offset: Int,
)

/** 이 지점에서 일어난 변화. `then` 칸의 재료다. */
data class EvidenceEffect(
    /** `write` · `ui-value` · `active-state` · `transform` · `scene` · `animation` · `destroy` · `instantiate` · `saved` · `audio`. */
    val kind: String,
    /** `state` · `observable` · `availability`. 문서가 [kind] 마다 결정론적으로 준다. */
    val category: String,
    /** 점 표기 대상(`Player.HpText.text`), 씬 이름, `this`, 또는 `(not a simple receiver)`. */
    val target: String?,
    val detail: String?,
    /** 이 효과를 선언한 메서드의 시그니처. 레코드의 `source` 와 다를 수 있다. */
    val source: String?,
    val offset: Int,
)

/** 런타임에 이벤트에 매단 처리기. `AddListener` 로만 물린 배선이 여기 남는다. */
data class EvidenceHandle(
    /** `CombineZone.activateButton.onClick` 등. 없을 수 있다. */
    val channel: String?,
    val channelType: String?,
    /** `AddListener` 등. */
    val member: String?,
    val handler: String?,
    /** 매달린 메서드의 안정 키. 배선 조인의 셋째 길이다. */
    val handlerId: String?,
    val offset: Int,
)

/**
 * 씬 오브젝트에 놓이지 못한 타입의 근거 묶음.
 *
 * `createdBy` 가 차 있으면 죽은 코드가 아니라 **주소가 없는 것**이다 — 프리팹 위에만 살아 씬 오브젝트의
 * 컴포넌트 목록에 나타나지 않는다.
 */
data class UnplacedType(
    val evidence: List<EvidenceRecord>,
    /**
     * 이 타입을 만드는 필드들. schema 6 에서는 **문자열** `"<OwnerType>.<field>"` 다.
     * 예: `Combat.Enemies.EnemyPoolController.enemyDataContainer`.
     *
     * ScriptableObject 를 거쳐 두 홉까지 추적된 결과가 이미 이 한 문자열에 담겨 있다.
     */
    val createdBy: List<String>,
    /**
     * 이 타입을 부르는 것들. **`types[].calledBy` 와 형식이 다르다** — 여기는 타입 이름(`Cards.CardManager`),
     * 저기는 메서드 안정 키다.
     */
    val calledBy: List<String>,
)

/** 씬에 실제로 놓인 오브젝트. */
data class SceneObject(
    /** `Canvas/continue` 처럼 `/` 로 이은 계층 경로. 사람용이다. */
    val path: String,
    /** `Canvas[2]/continue[2]` — 형제 인덱스를 실은 조준용 경로. */
    val selector: String?,
    val scene: String,
    val active: Boolean,
    val components: List<SceneComponent>,
    /** 스프라이트·글자 등 화면에 보이는 것. 라벨의 출처다. */
    val visuals: List<SceneVisual>,
) {
    /** 이 오브젝트에 쓰인 글자 — `control-caption` · `observed-text` 순. 없으면 null. */
    val label: String?
        get() = visuals.firstOrNull { it.role == "control-caption" }?.value
            ?: visuals.firstOrNull { it.role == "observed-text" }?.value
}

data class SceneComponent(
    val type: String,
    /** 인스펙터가 물어 둔 배선. 실측 7건이 문서 전체의 실배선 전부다. */
    val calls: List<ComponentCall>,
    val refs: List<ComponentRef>,
)

/** 인스펙터 이벤트 배선 — "이 컨트롤을 누르면 저 타입의 저 메서드를 부른다". */
data class ComponentCall(
    /** `m_OnClick` 등. */
    val event: String,
    val targetType: String,
    /** 호출 대상 컴포넌트가 붙은 오브젝트 경로. */
    val targetPath: String?,
    val method: String,
)

/** 컴포넌트가 든 참조. 프리팹 참조가 스폰 귀속의 다리다. */
data class ComponentRef(
    val field: String,
    val type: String,
    val name: String?,
    val id: Int?,
    /** 씬 오브젝트 참조면 그 경로, 에셋 참조면 null. */
    val path: String?,
    val asset: Boolean,
    /** 이 참조가 가리키는 프리팹에 실린 컴포넌트 타입들. 실측 70건 중 3건만 차 있다. */
    val carries: List<String>,
)

data class SceneVisual(
    /** `sprite` · `observed-text` · `control-caption`. */
    val role: String,
    val value: String?,
    val from: String?,
    val type: String?,
)

/**
 * 조건 트리 노드.
 *
 * **평탄화 금지** — `either` 가 `every` 로 뒤집히면 "둘 중 하나"가 "둘 다"가 된다. 이 모델은 원본
 * 모양을 그대로 담고, 원본 JSON 은 [EvidenceRecord.conditionJson] 이 따로 든다.
 */
sealed interface ConditionNode {

    /** 조건 없음. 실측 85건. */
    data object Always : ConditionNode

    /**
     * 비교 하나.
     *
     * [context] 가 null 이면 주어를 못 찾은 것이고 [subjectLost] 가 그 사유를 든다(실측 47건, 항상 동반).
     * **그 조건은 given 으로 쓸 수 없다** — 여기서 주어를 상상하는 것이 가장 비싼 거짓 명세다.
     */
    data class Test(
        val left: String,
        val operator: String,
        val right: String,
        val context: String?,
        val offset: Int,
        val subjectLost: String? = null,
    ) : ConditionNode

    /** 입력 조건. [input] 은 `key:RightArrow (down)` 처럼 이미 렌더된 문자열이다. */
    data class Gesture(val input: String, val offset: Int) : ConditionNode

    /** `every` — 모두 성립. `either` — 하나만 성립하면 된다. */
    data class Group(val kind: GroupKind, val parts: List<ConditionNode>) : ConditionNode

    /** 문서가 못 읽은 조건. 있으면 조건을 단정하면 안 된다. */
    data class Unknown(val reason: String?, val unread: String?) : ConditionNode
}

enum class GroupKind(val wire: String) {
    EVERY("every"),
    EITHER("either"),
    ;

    companion object {
        fun from(wire: String): GroupKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 트리를 깊이 우선으로 편다. 순서는 문서 순서다. */
fun ConditionNode.flatten(): List<ConditionNode> =
    listOf(this) + if (this is ConditionNode.Group) parts.flatMap { it.flatten() } else emptyList()
