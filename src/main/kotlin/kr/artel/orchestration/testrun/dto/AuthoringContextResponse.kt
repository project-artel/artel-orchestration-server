package kr.artel.orchestration.testrun.dto

import kr.artel.orchestration.testcase.dto.AuthoringTestCase
import kr.artel.orchestration.testcase.dto.SceneEdge

/**
 * 시나리오를 짜는 데 쓰는 게임 정보. 콘솔의 저작 Agent 가 세션을 열 때 받는 것과 같다.
 *
 * @property entryScene 게임을 켜면 처음 보이는 화면. 빌드가 알려 주지 않았으면 null.
 * @property startingValues 새 게임에서 상태 이름마다 시작 값.
 * @property sceneEdges 게임 지도의 화면 이동 전부와, 아는 경우 그 조작.
 * @property cases TR 버전의 TC. 지도에서 읽은 전후 상태(`state_before` · `state_after`)와 나가는 화면(`exits`)이 붙는다.
 */
data class AuthoringContextResponse(
    val entryScene: String?,
    val startingValues: Map<String, String>,
    val sceneEdges: List<SceneEdge>,
    val cases: List<AuthoringTestCase>,
)
