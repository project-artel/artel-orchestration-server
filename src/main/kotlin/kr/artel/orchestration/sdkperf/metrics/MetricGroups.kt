package kr.artel.orchestration.sdkperf.metrics

/**
 * 지표군을 값으로 다루는 자리 (ARTEL-435).
 *
 * 군마다 코틀린 타입을 하나씩 만들면 군이 늘 때마다 DTO·집계·마이그레이션이 같이 움직인다.
 * 여기서는 군 이름과 잎 경로를 데이터로 두어, 서버가 모르는 군도 저장되고 응답에 흐르게 한다.
 *
 * `coding-style.md`의 `Data Shapes`가 정한 예외 두 가지에 해당한다 — 군 payload는 그대로
 * `jsonb`에 넣는 passthrough이고, 그 스키마는 설계상 열려 있다. 그 조항이 요구하는 "다음
 * 단계에서 타입화"는 [MetricGroupReader]가 맡는다: 원시 맵은 여기서 한 번만 읽히고, 그
 * 아래로는 [MetricGroupSample]과 [MetricLeaf]만 흐른다. 원시 맵을 더 깊이 들고 다니지 않는다.
 */

/** 군 payload에서 뽑은 숫자 잎 하나. `collections.gen0`처럼 점으로 이어진 경로를 쓴다. */
data class MetricLeaf(val path: String, val value: Double)

/**
 * 한 표본에 실려 온 한 지표군.
 *
 * @property payload 받은 그대로. 원본 테이블에 이것을 넣는다 — 이해한 잎만 남기면 롤업 규칙이
 *   바뀌었을 때 복원할 수 없다.
 * @property source 숫자가 아닌 군 속성. 지금은 `renderCounters.source`뿐이다. Editor
 *   `UnityStats`와 Standalone `ProfilerRecorder`는 이름이 같아도 다른 값이라, 출처를 잃으면
 *   빌드 간 비교가 조용히 망가진다.
 */
data class MetricGroupSample(
    val name: String,
    val payload: Map<String, Any?>,
    val source: String?,
    val leaves: List<MetricLeaf>
)

/**
 * 모르는 군을 그대로 받되, 그 신뢰의 대가를 상한으로 막는다.
 *
 * SDK 버그가 군 이름을 매 프레임 새로 만들어 보내면 롤업 테이블이 런 하나로 무한히 늘어난다.
 * 상한을 넘으면 넘은 만큼만 버리고 나머지는 정상 저장한다 — 표본 하나를 통째로 버리면
 * 프레임 지표까지 함께 사라진다.
 */
object MetricGroupReader {
    const val MAX_GROUPS_PER_SAMPLE = 32
    const val MAX_LEAVES_PER_GROUP = 64
    const val MAX_GROUP_NAME_LENGTH = 64
    const val MAX_LEAF_PATH_LENGTH = 128

    /**
     * `sdk_performance_run_group.source`의 폭과 같아야 한다. 넘치면 22001로 표본 저장 전체가
     * 롤백되고, 프레임 지표까지 함께 사라진다 — 상한이 막으려던 바로 그 실패다.
     */
    const val MAX_SOURCE_LENGTH = 32

    /** 중첩 상한. 계약의 가장 깊은 잎이 `collections.gen0`(2단계)이라 넉넉하다. */
    private const val MAX_DEPTH = 4

    fun read(groups: Map<String, Map<String, Any?>>): List<MetricGroupSample> =
        groups.asSequence()
            .filter { (name, _) -> name.isNotBlank() && name.length <= MAX_GROUP_NAME_LENGTH }
            .take(MAX_GROUPS_PER_SAMPLE)
            .map { (name, payload) ->
                MetricGroupSample(
                    name = name,
                    payload = payload,
                    source = (payload["source"] as? String)?.take(MAX_SOURCE_LENGTH),
                    leaves = flatten(payload)
                )
            }
            .toList()

    private fun flatten(payload: Map<String, Any?>): List<MetricLeaf> {
        val leaves = mutableListOf<MetricLeaf>()
        collect(payload, prefix = "", depth = 0, into = leaves)
        return leaves
    }

    private fun collect(node: Map<*, *>, prefix: String, depth: Int, into: MutableList<MetricLeaf>) {
        if (depth >= MAX_DEPTH) return
        for ((rawKey, value) in node) {
            if (into.size >= MAX_LEAVES_PER_GROUP) return
            val key = rawKey as? String ?: continue
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (path.length > MAX_LEAF_PATH_LENGTH) continue
            when (value) {
                is Map<*, *> -> collect(value, path, depth + 1, into)
                // 불리언·문자열은 롤업 대상이 아니다. 원본 payload에는 그대로 남는다.
                is Number -> {
                    val number = value.toDouble()
                    if (number.isFinite()) into.add(MetricLeaf(path, number))
                }
                else -> Unit
            }
        }
    }
}

/**
 * 잎 하나를 런·버킷 단위로 접는 방법.
 *
 * 기본은 [MEAN_AND_MAX]다. **모르는 군과 모르는 잎도 이 기본값으로 흐르므로, 군을 추가하는 데
 * 코드도 마이그레이션도 필요하지 않다.** 아래 표는 기본값이 틀리는 잎만 적는다.
 */
enum class MetricRollup { MEAN_AND_MAX, SUM }

object GroupRollupSpec {
    /**
     * 창마다의 **델타 카운터**. 평균은 무의미하고 합이 맞다 — 런 전체에서 GC가 240번 돌았다는
     * 사실이 필요하지, 창당 평균 2.4번이 필요한 것이 아니다.
     *
     * 새 카운터 잎이 생기면 여기 한 줄이다. 스키마는 그대로다.
     */
    private val counterLeaves: Map<String, Set<String>> = mapOf(
        "gc" to setOf(
            "collections.gen0",
            "collections.gen1",
            "collections.gen2",
            "hitchesCoincidingWithCollection"
        )
    )

    fun rollupFor(group: String, leafPath: String): MetricRollup =
        if (counterLeaves[group]?.contains(leafPath) == true) MetricRollup.SUM
        else MetricRollup.MEAN_AND_MAX
}

/**
 * 응답에 언제나 자리를 갖는 군.
 *
 * 목록에 있는 군은 값이 한 번도 오지 않아도 응답에 나타난다 — `NOT_REPORTED`로. 키가 아예
 * 빠지면 화면은 "이 서버가 이 군을 모르는 것"과 "이 SDK가 이 군을 안 보내는 것"을 구분할 수
 * 없다. 목록 밖의 군도 실제로 값이 오면 그대로 응답에 실린다.
 *
 * 군 추가는 여기 한 줄이다. 마이그레이션도 집계 코드도 필요 없다. 군을 없앨 때도 즉시 빼지
 * 않고 한 릴리스 이상 `NOT_REPORTED`로 남긴 뒤 뺀다.
 */
object KnownMetricGroups {
    val names: Set<String> = linkedSetOf(
        // 기존 SDK 지표군. Editor Game view 렌더 통계(UnityStats 출처)와 CPU·GPU 프레임타임 분해.
        "editorRender",
        "frameTiming",
        // SDK 미구현 (ARTEL-350/351/352). 구현 전까지 NOT_REPORTED로 내려간다.
        "gc",
        "renderCounters",
        "sdkOverhead"
    )
}

/**
 * 롤업 행을 계약이 정한 응답 모양으로 되돌린다.
 *
 * 잎 경로는 점으로 이어져 있고(`collections.gen0`) 응답은 중첩 객체다. 접두 규칙은
 * [GroupRollupSpec]이 정한 종류를 따른다 — 카운터는 경로 그대로, 그 밖은 `Mean`/`Max` 접미.
 */
object MetricRollupAssembler {
    data class LeafRollup(val path: String, val sampleCount: Long, val sum: Double, val max: Double)

    fun metrics(group: String, leaves: List<LeafRollup>): Map<String, Any?> {
        val flat = linkedMapOf<String, Number>()
        for (leaf in leaves) {
            when (GroupRollupSpec.rollupFor(group, leaf.path)) {
                // 카운터의 합은 횟수다. 계약의 예시(`"gen0": 240`)와 같은 모양으로 내보낸다.
                MetricRollup.SUM -> flat[leaf.path] = whole(leaf.sum)
                MetricRollup.MEAN_AND_MAX -> {
                    if (leaf.sampleCount > 0) flat["${leaf.path}Mean"] = leaf.sum / leaf.sampleCount
                    flat["${leaf.path}Max"] = leaf.max
                }
            }
        }
        return nest(flat)
    }

    private fun whole(value: Double): Number =
        if (value % 1.0 == 0.0 && value.isFinite()) value.toLong() else value

    private fun nest(flat: Map<String, Number>): Map<String, Any?> {
        val root = linkedMapOf<String, Any?>()
        leaves@ for ((path, value) in flat) {
            val segments = path.split('.')
            var node = root
            for (segment in segments.dropLast(1)) {
                val child = node.getOrPut(segment) { linkedMapOf<String, Any?>() }
                // 같은 이름이 한 번은 잎, 한 번은 가지로 오면 먼저 온 값을 지키고 이 경로는 버린다.
                @Suppress("UNCHECKED_CAST")
                node = (child as? LinkedHashMap<String, Any?>) ?: continue@leaves
            }
            // 반대 방향도 같다. 이미 가지가 선 자리에 잎을 덮으면 그 아래가 통째로 사라진다.
            val leaf = segments.last()
            if (node[leaf] !is Map<*, *>) node[leaf] = value
        }
        return root
    }
}
