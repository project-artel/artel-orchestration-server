package kr.artel.orchestration.contentmap.dto

/**
 * 이 기능의 코드가 **어느 타입에 붙어 있나**(`capability_evidence.method_id`).
 *
 * 효과의 대상이 `Component.transform.position` 처럼 적히는 일이 있다(실측 18행). `Component` 는
 * 유니티의 밑바탕 타입이라 **게임에 그 이름의 오브젝트가 없다** — 코드가 자기 자신을 가리킬 때
 * 수신자의 선언 타입이 그렇게 적힐 뿐이다. 그것이 가리키는 것은 그 코드가 붙어 있는 컴포넌트이고,
 * 그 이름을 `method_id` 가 들고 있다.
 *
 * @property methodId `Assembly-CSharp|Combat.UI.DraggableCard|OnDrag|System.Void(…)` 꼴.
 */
data class CapabilityOwner(
    val capabilityId: Long,
    val methodId: String,
)
