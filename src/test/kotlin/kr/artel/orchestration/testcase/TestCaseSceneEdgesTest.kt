package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.testcase.dto.SceneEdge
import kr.artel.orchestration.testcase.dto.SceneExitRow
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testscenario.service.AuthoringCases
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class TestCaseSceneEdgesTest {
    private val authoringCases = mock<AuthoringCases>()
    private val service = TestCaseService(
        mock(), mock(), mock(), ObjectMapper(), mock(), mock(), mock(), mock(), mock(),
        authoringCases, mock(), mock(), mock(), mock(),
    )

    @Test
    fun `같은 화면 간선의 조작은 조회 순서와 관계없이 동일하다`(): Unit = runBlocking {
        val rows = listOf(
            SceneExitRow("Title", "Map", null),
            SceneExitRow("Title", "Map", "Canvas/continue"),
            SceneExitRow("Title", "Map", "Canvas/MapSceneButton"),
            SceneExitRow("Map", "Battle", null),
        )
        Mockito.`when`(authoringCases.sceneExits(1)).thenReturn(rows.asFlow(), rows.reversed().asFlow())

        val first = service.sceneEdges(1)
        val reversed = service.sceneEdges(1)

        assertThat(reversed).isEqualTo(first)
        assertThat(first).containsExactly(
            SceneEdge("Map", "Battle", null),
            SceneEdge("Title", "Map", "Canvas/MapSceneButton"),
        )
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
