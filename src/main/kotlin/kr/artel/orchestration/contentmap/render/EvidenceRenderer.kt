package kr.artel.orchestration.contentmap.render

/**
 * 오케스트레이션 — evidence JSON 텍스트를 받아 파일명 -> 의사 C# 내용 맵을 낸다.
 * `wv2cs.py::main()` 의 렌더 부분과 대응. 컨트롤러/서비스 계층은 이 스코프 밖이다
 * (호출부 없음 — 다음 이슈가 붙인다).
 *
 * 순수 함수: 같은 입력이면 항상 같은 출력(결정론). DB/네트워크 접근 없음.
 */
fun renderEvidenceDocument(json: String): Map<String, String> {
    val document = EvidenceDocument.parse(json)
    val wiringIndex = WiringIndex.build(document)
    val header = listOf("// ${document.captureHeaderLine}")

    val files = LinkedHashMap<String, String>()

    for ((typeName, records) in document.types) {
        files["$typeName.cs"] = renderType(typeName, records, header, wiringIndex)
    }

    for ((typeName, blob) in document.unplaced) {
        val extra = buildList {
            addAll(header)
            add("// UNPLACED: no scene object proven to host this type")
            if (blob.createdBy.isNotEmpty()) add("// created by: " + blob.createdBy.joinToString(", "))
        }
        files["_unplaced/$typeName.cs"] = renderType(typeName, blob.evidence, extra, wiringIndex)
    }

    val scenes = document.scenes.toMutableList()
    for (o in document.persistentObjects) {
        val scene = o.path("scene").textOrNull()
        if (scene != null && scene !in scenes) scenes += scene
    }
    files["_SceneGraph.cs"] = renderSceneGraph(document.objects + document.persistentObjects, scenes)

    val notes = buildList {
        add("// capture notes")
        add("")
        addAll(header)
        add("")
        add("// scenes: " + document.scenes.joinToString(", "))
        add("// capabilities: " + document.capabilities.joinToString(", "))
        document.gaps.forEach { add("// gap: $it") }
    }
    files["_Notes.cs"] = notes.joinToString("\n") + "\n"

    return files
}
