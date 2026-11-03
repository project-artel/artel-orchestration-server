package kr.artel.orchestration.testcase.transfer

/**
 * TC 파일의 칸(ARTEL-1327). 내보낼 때는 [label] 로 쓰고, 가져올 때는 [aliases] 중 어느 머리글이든 알아본다 — 한국어 · 영어,
 * 그리고 MCP 가 쓰는 목록 파일(`id,change,kind,scene,…`)의 머리글까지.
 *
 * [NUMBER] 는 화면의 TC 번호(`TC-012`, 프로젝트 안 번호)이고 [ID] 는 DB 의 TC id 다. 내보내기는 번호만 쓴다. MCP 목록 파일은
 * DB id 를 `id` 칸에 쓰므로 가져올 때 둘 다 받는다.
 */
enum class CaseFileColumn(val label: String, val aliases: Set<String>, val exported: Boolean = true) {
    NUMBER("번호", setOf("번호", "tc번호", "tc", "number", "casenumber", "no")),
    ID("id", setOf("id", "testcaseid", "test_case_id"), exported = false),
    KIND("종류", setOf("종류", "kind", "type")),
    SCENE("화면", setOf("화면", "scene", "장소")),
    PRECONDITION("사전조건", setOf("사전조건", "precondition")),
    STEP("조작", setOf("조작", "스텝", "내용", "step")),
    EXPECTED("기대결과", setOf("기대결과", "expected", "expectedvalue", "기댓값")),
    ARRIVES_AT("도착 화면", setOf("도착화면", "arrivesat", "arrival")),
    RESULT("결과", setOf("결과", "result", "verificationstatus")),
    NEEDS("필요", setOf("필요", "needs")),
    LEAVES("남김", setOf("남김", "leaves"));

    companion object {
        /** 내보내는 칸의 순서. */
        val EXPORTED: List<CaseFileColumn> = entries.filter { it.exported }

        /** 가져올 때 꼭 있어야 하는 칸. */
        val REQUIRED: List<CaseFileColumn> = listOf(SCENE, STEP, EXPECTED)

        /** 머리글 하나를 칸으로. 띄어쓰기 · 밑줄 · 대소문자를 가리지 않는다. 모르는 머리글은 null(무시한다). */
        fun of(header: String): CaseFileColumn? {
            val key = normalize(header)
            if (key.isEmpty()) return null
            return entries.firstOrNull { column -> column.aliases.any { normalize(it) == key } }
        }

        private fun normalize(text: String): String =
            text.trim().removePrefix("﻿").lowercase().replace(Regex("[\\s_\\-()]"), "")
    }
}

/** 파일 한 줄. [line] 은 사람이 보는 줄 번호다(머리글이 1). */
data class CaseFileRow(val line: Int, val cells: Map<CaseFileColumn, String>) {
    operator fun get(column: CaseFileColumn): String = cells[column]?.trim().orEmpty()
}

enum class CaseFileFormat(val extension: String, val contentType: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv; charset=UTF-8");

    companion object {
        fun of(name: String?): CaseFileFormat? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
