package kr.artel.orchestration.testscenario.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 저작을 **모델에게 되돌려 보는 실험**의 스위치(`artel.scenario.experiment`).
 *
 * 2026-08-31 에 묶기와 순서를 계산으로 옮겼다. 그때의 근거는 유효했지만 **그때의 조건**에서
 * 잰 것이다 — 경로 답이 틀려 있었고(막힘 1,273칸 중 88%가 헛막힘), 케이스는 42건이었고,
 * 모델도 지금 것이 아니었다. 셋 다 바뀌었으므로 다시 잰다.
 *
 * **지우지 않고 끄는 것으로 실험한다.** 지면 스위치만 되돌리면 되고, 이기면 그때 걷어낸다.
 */
@ConfigurationProperties(prefix = "artel.scenario.experiment")
data class AuthoringExperimentProperties(

    /**
     * 흐름을 세워 보낼 것인가.
     *
     * 끄면 짝 행렬도 안 푼다 — 흐름을 안 보낼 것이면 그 계산은 아무 데도 안 쓰인다. 케이스
     * 88건에서 7,656칸과 11.2초가 통째로 빠진다.
     *
     * agent 쪽 프롬프트는 흐름이 비어 있으면 스스로 묶고 세운다(`v8/system.md` 0a 의 마지막
     * 줄). 그래서 이 스위치 하나로 예전 구조가 그대로 선다.
     */
    val flows: Boolean = true,

    /**
     * 모델에게 줄 추론 예산(token). 0 이면 안 켠다 — 지금까지의 저작이 그랬다.
     *
     * **모델마다 받는 설정이 다르다.** Anthropic 은 `effort` 를 안 받고 예산으로만 켜므로
     * 여기도 예산이다. 모델이 그 설정을 못 받으면 agent 가 **세션을 열 때** 거절한다 —
     * 조용히 낮추지 않는다.
     *
     * Haiku 4.5 는 최소 1,024 · 최대 32,000 이고 사양의 기본값은 4,096 이다(Luna 의 medium 이
     * 런당 1,500~5,000 을 쓴 것에 맞춘 값). 저작이 그보다 무거운지는 안 재 봤다.
     */
    val reasoningMaxTokens: Int = 0,
)
