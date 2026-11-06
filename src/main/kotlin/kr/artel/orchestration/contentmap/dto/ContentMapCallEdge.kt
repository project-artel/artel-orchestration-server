package kr.artel.orchestration.contentmap.dto

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column

/**
 * **누가 무엇을 부르나** 한 줄(ARTEL-554).
 *
 * 조작 갈래와 결과 갈래를 잇는 유일한 인과다. 코루틴·상태 머신에서는 입력을 받는 갈래와 결과를
 * 내는 갈래가 다른 기능 행이고, 공통 호출자를 통해서만 이어진다.
 *
 * @property callerMethodId 부른 **메서드**. 기능 행이 아니다 — 코루틴 하나가 갈래 여럿으로 쪼개지고
 *   각 갈래가 호출을 하나씩만 들어서, 행으로 묶으면 같은 메서드가 부르는 것들이 흩어진다.
 * @property callerCondition 그 호출이 일어나는 조건. **이은 케이스의 사전조건이 이것도 들어야 한다** —
 *   조건 아래에서만 부르기 때문이다.
 * @property capabilityId 불린 기능. 확인할 수 있는 효과를 든 것만 나온다.
 * @property conditionTree 불린 쪽의 조건.
 */
data class ContentMapCallEdge(
    @Column("caller_method_id")
    val callerMethodId: String,

    @Column("caller_condition")
    val callerCondition: Json?,

    @Column("capability_id")
    val capabilityId: Long,

    @Column("condition_tree")
    val conditionTree: Json?,
)
