package kr.artel.orchestration.scenecontext.service

/**
 * `macro` 원문에서 진입점 `def` 본문의 **첫 `#` 주석 줄**을 뽑는다(ARTEL-934). 앞의 `#` 과 공백은
 * 떼고, 주석이 없으면 null 이다.
 *
 * 저장된 tree(`definition_json`)에서 뽑지 않는 이유: `ast.parse` 가 주석을 버리므로 tree 에는
 * 주석이 없다(`MacroEntity.source` 의 KDoc). 주석은 원문에만 남는다.
 *
 * 파서를 쓰지 않고 줄 단위로 읽는다. 원문 문법은 docstring 도 여러 줄 문자열도 거절하므로(저장 때
 * 걸러진다) 줄이 `#` 으로 시작하면 주석이다. 문장 끝에 붙은 주석(`click(x)  # 설명`)은 줄이
 * `#` 으로 시작하지 않아 고르지 않는다 — 요약은 저자가 따로 한 줄로 적은 것만 믿는다.
 *
 * 본문의 범위는 `def <name>(` 줄 다음부터 들여쓰기 없는 다음 줄 앞까지다. 그래서
 * - `def` 앞에 있는 주석(파일 머리말)은 요약이 아니다
 * - helper `def` 가 진입점 뒤에 있어도 그 본문의 주석을 가져오지 않는다
 * - 진입점이 helper 뒤에 있어도 [entryName] 으로 찾는다
 *
 * `#` 뒤가 비어 있는 줄은 건너뛰고 다음 주석을 본다. 빈 요약은 요약이 없는 것과 같다.
 */
internal fun firstBodyComment(source: String, entryName: String): String? {
    val lines = source.lines()
    val definitionLine = Regex("^def\\s+${Regex.escape(entryName)}\\s*\\(")
    val start = lines.indexOfFirst { definitionLine.containsMatchIn(it) }
    if (start < 0) return null

    return lines.drop(start + 1)
        .takeWhile(::continuesDefinition)
        .map(String::trim)
        .firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("#") }?.removePrefix("#")?.trim()?.takeIf(String::isNotEmpty)
        }
}

/**
 * 이 줄이 아직 같은 `def` 안인가. 빈 줄과 들여쓴 줄은 안이다. 들여쓰지 않은 줄은 다음 최상위 문장이
 * 시작된 것이라 끝이다 — 다만 여러 줄에 걸친 signature 의 닫는 `)` 는 들여쓰기가 없어도 안이다.
 */
private fun continuesDefinition(line: String): Boolean =
    line.isBlank() || line.first().isWhitespace() || line.startsWith(")")
