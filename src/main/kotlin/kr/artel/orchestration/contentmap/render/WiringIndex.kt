package kr.artel.orchestration.contentmap.render

/**
 * fix 2: `triggerKind: unity-event` 는 "인스펙터가 부를 수 있는 모양"이지 배선됐다는 뜻이
 * 아니다(실측: unity-event 레코드 112건(unplaced 포함 172건) vs 실제 배선 7건). 실배선은
 * 같은 문서의 `objects[].components[].calls[]`(+ `persistentObjects[]`)에만 있다 —
 * 씬 오브젝트의 컴포넌트가 실제로 어떤 (타입, 메서드) 를 부르는지.
 *
 * `TypeRenderer`(메서드에 `[UnityEvent(wired: ...)]`/`[InspectorCallable]` 를 붙이는 쪽)와
 * `SceneGraphRenderer`(같은 배선 사실을 `_SceneGraph.cs` 에 다른 모양으로 이미 쓰는 쪽) 둘 다
 * 이 사실이 필요해서 한 번만 계산해 공유한다.
 *
 * 매칭은 owner 타입 풀네임과 메서드 이름의 **완전 문자열 일치**만 본다 — 파라미터 개수/타입은
 * 보지 않는다(`calls[].method` 자체가 이름만 담고 오버로드 구분 정보가 없고, 실측에도
 * 오버로드 충돌이 없다). 제네릭 소거나 별칭 정규화도 하지 않는다 — 근거에 없는 사례를 위한
 * 규칙을 새로 지어내는 것은 이 이슈의 "근거에 없는 것을 코드로 지어내지 않는다"는 제약과
 * 어긋난다. 일치하지 않으면 안전한 기본값([InspectorCallable])으로 떨어진다.
 *
 * 실측 확인: 배선 7건 중 `Combat.UI.CombineZone::OnButtonClick` 은 evidence 어디에도
 * 재구성된 메서드 레코드가 없다(문서가 그 본문을 못 봤다 — gap). 인덱스는 여전히 7개
 * 항목을 다 갖지만, 실제로 `[UnityEvent(wired: ...)]` 주석이 붙는 메서드는 6개뿐이다 —
 * 매칭 규칙이 깨진 게 아니라 evidence 자체의 공백이다.
 */
class WiringIndex private constructor(private val pathsByTarget: Map<Pair<String, String>, List<String>>) {

    /** 이 (owner 타입 풀네임, 메서드 이름) 을 실제로 부르는 씬 오브젝트 경로들. 없으면 빈 목록. */
    fun wiringsFor(ownerFullType: String, methodName: String): List<String> =
        pathsByTarget[ownerFullType to methodName].orEmpty()

    /** 인덱싱된 배선 항목 총 개수(테스트 전용 — 매칭 성공 여부와 별개로 "몇 건이 있었는지"). */
    val entryCount: Int get() = pathsByTarget.values.sumOf { it.size }

    companion object {
        fun build(document: EvidenceDocument): WiringIndex {
            val map = LinkedHashMap<Pair<String, String>, MutableList<String>>()
            for (obj in document.objects + document.persistentObjects) {
                val path = obj.path("path").textOrNull() ?: continue
                for (component in obj.path("components").arrayOrEmpty()) {
                    for (call in component.path("calls").arrayOrEmpty()) {
                        val targetType = call.path("targetType").textOrNull() ?: continue
                        val method = call.path("method").textOrNull() ?: continue
                        map.getOrPut(targetType to method) { mutableListOf() }.add(path)
                    }
                }
            }
            return WiringIndex(map)
        }
    }
}
