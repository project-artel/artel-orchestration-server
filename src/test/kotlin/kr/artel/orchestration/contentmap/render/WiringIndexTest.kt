package kr.artel.orchestration.contentmap.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * fix 2 의 매칭 규칙. 실측 문서에는 배선 7건이 있지만, 그중
 * `Combat.UI.CombineZone::OnButtonClick` 은 evidence 에 재구성된 레코드가 없어 실제로
 * `[UnityEvent(wired: ...)]` 로 나타나는 메서드는 6개뿐이다(golden 스냅샷에서 확인) — 인덱스
 * 자체는 여전히 7건을 다 갖는다는 것과, 대표적인 매칭/비매칭 사례를 여기서 확인한다.
 */
class WiringIndexTest {

    private fun realDocument(): EvidenceDocument {
        val json = javaClass.getResourceAsStream("/contentmap/wv-editor-latest.json")!!.bufferedReader().readText()
        return EvidenceDocument.parse(json)
    }

    @Test
    fun `실측 문서에서 배선 7건을 인덱싱한다`() {
        val index = WiringIndex.build(realDocument())

        assertThat(index.entryCount).isEqualTo(7)
    }

    @Test
    fun `Canvas continue 는 TitleSceneManager LoadStoryScene 에 배선돼 있다`() {
        val index = WiringIndex.build(realDocument())

        assertThat(index.wiringsFor("Scenes.TitleSceneManager", "LoadStoryScene")).containsExactly("Canvas/continue")
    }

    @Test
    fun `배선이 없는 메서드는 빈 목록을 반환한다`() {
        val index = WiringIndex.build(realDocument())

        assertThat(index.wiringsFor("Combat.Enemies.BattleWaveController", "StartWave")).isEmpty()
    }

    @Test
    fun `persistentObjects 의 배선도 objects 와 똑같이 인덱싱된다`() {
        val json = """
            {
              "schema": 6, "capture": "editor", "build": {}, "scenes": [], "types": {}, "unplaced": {},
              "objects": [],
              "persistentObjects": [
                {"path": "DontDestroyOnLoad/Manager", "scene": "S", "components": [
                  {"type": "UnityEngine.UI.Button", "calls": [
                    {"event": "m_OnClick", "targetType": "NS.Foo", "method": "Bar", "targetPath": "T"}
                  ]}
                ]}
              ],
              "gaps": []
            }
        """.trimIndent()
        val index = WiringIndex.build(EvidenceDocument.parse(json))

        assertThat(index.entryCount).isEqualTo(1)
        assertThat(index.wiringsFor("NS.Foo", "Bar")).containsExactly("DontDestroyOnLoad/Manager")
    }

    @Test
    fun `합성 문서에서 targetType 과 method 가 둘 다 완전히 일치해야 매칭된다`() {
        val json = """
            {
              "schema": 6, "capture": "editor", "build": {}, "scenes": [], "types": {}, "unplaced": {},
              "objects": [
                {"path": "Canvas/Button", "scene": "S", "components": [
                  {"type": "UnityEngine.UI.Button", "calls": [
                    {"event": "m_OnClick", "targetType": "NS.Foo", "method": "Bar", "targetPath": "T"}
                  ]}
                ]}
              ],
              "persistentObjects": [], "gaps": []
            }
        """.trimIndent()
        val index = WiringIndex.build(EvidenceDocument.parse(json))

        assertThat(index.wiringsFor("NS.Foo", "Bar")).containsExactly("Canvas/Button")
        assertThat(index.wiringsFor("NS.Foo", "Baz")).isEmpty()
        assertThat(index.wiringsFor("NS.Other", "Bar")).isEmpty()
    }
}
