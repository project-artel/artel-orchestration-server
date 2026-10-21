package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json

/**
 * 유니티 빌드 설정의 씬 순서(ARTEL-659 · ARTEL-1173).
 *
 * SDK 의 `scene_scan.scenesInBuild` 가 빌드 설정 순서 그대로이고 **0번이 부팅 씬**이다. 유니티의 규약이지
 * 명세가 적어 준 것이 아니라, "어떻게 아는지"는 이 함수 하나에만 둔다 — 입구 씬을 적는 적재기와 씬 순서를
 * 내보내는 조회가 같은 읽기를 쓴다. 다른 엔진이 붙으면 여기만 손본다.
 */
object SceneBuildOrder {

    /**
     * 씬 이름을 빌드 설정 순서로. `Assets/Scenes/TitleScene.unity` → `TitleScene`. scan 이 없으면 빈 목록이고,
     * JSON 이 깨졌으면 던진다 — 부르는 쪽이 로그를 남길지 정한다.
     */
    fun names(sceneScan: Json?, objectMapper: ObjectMapper): List<String> {
        if (sceneScan == null) return emptyList()
        return objectMapper.readTree(sceneScan.asString()).path("scenesInBuild").mapNotNull { node ->
            node.asText(null)?.substringAfterLast('/')?.removeSuffix(".unity")?.takeIf { it.isNotBlank() }
        }
    }
}
