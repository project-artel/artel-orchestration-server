package kr.artel.orchestration.testcase.transfer

/**
 * TC 파일의 칸(ARTEL-1327). 내보낼 때는 [label] 로 쓰고, 가져올 때는 [aliases] 중 어느 머리글이든 알아본다 — 한국어 · 영어,
 * 그리고 MCP 가 쓰는 목록 파일(`id,change,kind,scene,…`)의 머리글까지.
 *
 * 계약 칸(필요 · 남김)은 싣지 않는다. 사람이 표에서 `열쇠 == 1` 꼴로 채우기 어렵고, 잘못 채우면 시나리오를 깨뜨린다. 계약은
 * 글에서 뽑거나 화면 · MCP 로 정한다. 모르는 머리글은 무시하므로 예전 파일의 필요 · 남김 칸도 그냥 지나간다.
 *
 * [NUMBER] 는 화면의 TC 번호(`TC-012`, 프로젝트 안 번호)다. **`Artel 번호` 라는 머리글로만 알아본다.** 사용자 파일의 `번호` ·
 * `No` · `TC 번호` · `ID` 칸은 사용자가 붙인 번호([USER_NUMBER])다 — 우리 번호와 겹쳐도 같은 TC 가 아니고, 같은 TC 를 찾는 데
 * 쓰지 않는다. 받은 그대로 TC 에 적어 두고(`metadata.user_number`) 내보낼 때 그대로 낸다. 겹쳐도 가리지 않는다.
 */
enum class CaseFileColumn(val label: String, val aliases: Set<String>, val exported: Boolean = true) {
    NUMBER("Artel 번호", setOf("artel번호", "artelnumber", "artelno")),
    USER_NUMBER("번호", setOf("번호", "사용자번호", "tc번호", "tc", "number", "casenumber", "no", "id")),
    KIND("종류", setOf("종류", "kind", "type")),
    SCENE("화면", setOf("화면", "scene", "장소")),
    PRECONDITION("사전조건", setOf("사전조건", "precondition")),
    STEP("조작", setOf("조작", "스텝", "내용", "step")),
    EXPECTED("기대결과", setOf("기대결과", "expected", "expectedvalue", "기댓값")),
    ARRIVES_AT("도착 화면", setOf("도착화면", "arrivesat", "arrival")),
    RESULT("결과", setOf("결과", "result", "verificationstatus"));

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
