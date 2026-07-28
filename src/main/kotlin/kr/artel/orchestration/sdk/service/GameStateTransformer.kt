package kr.artel.orchestration.sdk.service

import kr.artel.orchestration.sdk.dto.*

/**
 * Artel SDK로부터 전달받은 상세 게임 상태(SdkGameState)를
 * 에이전트 의사결정에 적합한 정제된 컴팩트 형태(AgentGameState)로 변환하는 오케스트레이터의 핵심 변환 유틸리티
 */
object GameStateTransformer {

    /**
     * 실행 기록 상한. 씬 전체의 액션을 다 실으면 프레임마다 커지고, 오래된 실행은
     * 판정에 쓰이지 않는다. 최신 것부터 이만큼만 남긴다.
     */
    private const val MAX_RECENT_ACTIONS = 20

    fun toAgentGameState(sdkGameState: SdkGameState): AgentGameState {
        val rootNode = sdkGameState.scene
        val sceneName = rootNode.name

        val interactables = mutableListOf<Interactable>()
        val observables = mutableMapOf<String, ObservableValue>()
        val actions = mutableListOf<Pair<Int, AgentActionRecord>>()

        // 씬 루트는 경로에서 뺀다. 이름이 이미 scene으로 나가므로 모든 키 앞에 한 번 더 붙을 뿐이다.
        traverse(rootNode, "", interactables, observables, actions)

        return AgentGameState(
            scene = sceneName,
            // 화면 크기는 씬 루트에만 실린다. 이것이 없으면 후보들의 픽셀 rect를 해석할 수 없다.
            screen = rootNode.screen?.let { AgentScreenSize(w = it.w, h = it.h) },
            interactables = interactables,
            observables = observables,
            // sequence가 실행 순서다. 최신 N개를 고른 뒤 다시 시간순으로 돌려 준다.
            recentActions = actions.sortedByDescending { it.first }
                .take(MAX_RECENT_ACTIONS)
                .map { it.second }
                .reversed()
        )
    }

    /** 씬 루트 바로 아래 블록은 자기 이름만 갖는다. 평평한 씬의 키가 종전과 같게 유지된다. */
    private fun childPath(path: String, name: String): String {
        return if (path.isEmpty()) name else "$path.$name"
    }

    /**
     * @param path 이 블록까지의 조상 이름을 `.`으로 이은 경로. 관찰값 키의 앞자리가 된다.
     *   블록 이름만으로 키를 만들면 서로 다른 패널의 같은 이름이 맵에서 서로를 덮어쓰고,
     *   어느 쪽이 남았는지 읽는 쪽에서 알 방법이 없다.
     */
    private fun traverse(
        node: SdkBlock,
        path: String,
        interactables: MutableList<Interactable>,
        observables: MutableMap<String, ObservableValue>,
        actions: MutableList<Pair<Int, AgentActionRecord>>
    ) {
        val components = node.components

        // 좌표는 컴포넌트가 아니라 블록에 실린다. 이 블록에서 나가는 모든 조작 후보가
        // 같은 값을 공유한다. SDK가 준 좌상단 픽셀 값을 변환 없이 그대로 옮긴다.
        val rect = node.transform?.rect?.let { AgentRect(x = it.x, y = it.y, w = it.w, h = it.h) }
        val onScreen = node.transform?.onScreen ?: true

        // 라벨로 흡수되는 text는 조작 후보로 나가는 버튼의 것뿐이다. 잠긴 버튼은 후보에서 빠지므로
        // 그 라벨은 아래에서 관찰값으로 남겨야 한다. 그러지 않으면 무엇이 잠겼는지가 두 목록
        // 어디에도 남지 않는다.
        val hasInteractableButton = components.any { it.type == "button" && it.interactable }

        for (component in components) {
            // 1. 조작 후보(Interactables) 추출
            //
            // 게임이 잠가 둔 UI는 후보가 아니다. 사람이 누를 수 없는 것을 에이전트에게 권하면
            // SDK가 거절하는 액션을 반복해서 고르게 된다.
            when (component.type) {
                "button" -> if (component.interactable) {
                    // 동일한 블록 내에 text 타입의 컴포넌트가 존재할 경우 그 content를 버튼 라벨로 채택
                    val textComp = components.find { it.type == "text" }
                    val label = textComp?.content
                    interactables.add(
                        Interactable(
                            id = node.id,
                            name = node.name,
                            type = "button",
                            label = label,
                            rect = rect,
                            onScreen = onScreen
                        )
                    )
                }
                "editText" -> if (component.interactable) {
                    interactables.add(
                        Interactable(
                            id = node.id,
                            name = node.name,
                            type = "editText",
                            placeholder = component.placeholder,
                            rect = rect,
                            onScreen = onScreen
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
                                actions = component.actions.map { it.name },
                                rect = rect,
                                onScreen = onScreen
                            )
                        )
                    }
                }
            }

            // 2. 관찰 값(Observables) 추출
            if (component.content != null) {
                // 버튼 라벨용으로 사용된 text 컴포넌트의 content는 중복 관찰대상에서 배제
                val skipObservable = (component.type == "text" && hasInteractableButton)
                if (!skipObservable) {
                    observables["$path.content"] = ObservableValue(
                        value = component.content,
                        type = "string"
                    )
                }
            }

            for (state in component.states) {
                observables["$path.${component.type}.${state.name}"] = ObservableValue(
                    value = state.value,
                    type = state.type
                )
            }

            // 3. 실행 기록(Action telemetry) 수집
            //
            // 컴포넌트 종류를 가리지 않는다. 위의 조작 후보 추출은 커스텀 컴포넌트만
            // actions를 보지만, 그것은 "무엇을 부를 수 있는가"를 고르는 자리다. 여기는
            // "무엇이 실행됐는가"이고, 버튼 클릭이야말로 QA가 확인하려는 것이다.
            for (action in component.actions) {
                actions.add(
                    action.sequence to AgentActionRecord(
                        target = node.name,
                        name = action.name,
                        success = action.success,
                        returnValue = action.returnValue,
                        error = action.error?.message,
                        at = action.timeStamp
                    )
                )
            }
        }

        // 자식 노드 재귀 탐색
        for (child in node.children) {
            traverse(child, childPath(path, child.name), interactables, observables, actions)
        }
    }
}
