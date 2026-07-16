package kr.artel.orchestration.sdk.service

import kr.artel.orchestration.sdk.dto.*

/**
 * Artel SDK로부터 전달받은 상세 게임 상태(SdkGameState)를
 * 에이전트 의사결정에 적합한 정제된 컴팩트 형태(AgentGameState)로 변환하는 오케스트레이터의 핵심 변환 유틸리티
 */
object GameStateTransformer {

    fun toAgentGameState(sdkGameState: SdkGameState): AgentGameState {
        val rootNode = sdkGameState.scene
        val sceneName = rootNode.name

        val interactables = mutableListOf<Interactable>()
        val observables = mutableMapOf<String, ObservableValue>()

        traverse(rootNode, interactables, observables)

        return AgentGameState(
            scene = sceneName,
            interactables = interactables,
            observables = observables
        )
    }

    private fun traverse(
        node: SdkBlock,
        interactables: MutableList<Interactable>,
        observables: MutableMap<String, ObservableValue>
    ) {
        val components = node.components
        val hasButton = components.any { it.type == "button" }

        for (component in components) {
            // 1. 조작 후보(Interactables) 추출
            when (component.type) {
                "button" -> {
                    // 동일한 블록 내에 text 타입의 컴포넌트가 존재할 경우 그 content를 버튼 라벨로 채택
                    val textComp = components.find { it.type == "text" }
                    val label = textComp?.content
                    interactables.add(
                        Interactable(
                            id = node.id,
                            name = node.name,
                            type = "button",
                            label = label
                        )
                    )
                }
                "editText" -> {
                    interactables.add(
                        Interactable(
                            id = node.id,
                            name = node.name,
                            type = "editText",
                            placeholder = component.placeholder
                        )
                    )
                }
                else -> {
                    // 커스텀 C# 스크립트 컴포넌트(ex: PlayerController) 중 정의된 액션 목록이 존재하는 경우 대상에 추가
                    if (component.actions.isNotEmpty()) {
                        interactables.add(
                            Interactable(
                                id = node.id,
                                name = node.name,
                                type = component.type,
                                actions = component.actions.map { it.name }
                            )
                        )
                    }
                }
            }

            // 2. 관찰 값(Observables) 추출
            if (component.content != null) {
                // 버튼 라벨용으로 사용된 text 컴포넌트의 content는 중복 관찰대상에서 배제
                val skipObservable = (component.type == "text" && hasButton)
                if (!skipObservable) {
                    observables["${node.name}.content"] = ObservableValue(
                        value = component.content,
                        type = "string"
                    )
                }
            }

            for (state in component.states) {
                observables["${node.name}.${component.type}.${state.name}"] = ObservableValue(
                    value = state.value,
                    type = state.type
                )
            }
        }

        // 자식 노드 재귀 탐색
        for (child in node.children) {
            traverse(child, interactables, observables)
        }
    }
}
