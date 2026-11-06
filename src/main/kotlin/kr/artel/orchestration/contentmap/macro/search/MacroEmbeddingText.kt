package kr.artel.orchestration.contentmap.macro.search

/**
 * macro 하나를 임베딩할 문장으로 만든다. **이 함수가 그 문장을 만드는 유일한 자리다.**
 *
 * 백필 소스가 이 문장을 임베딩하고, 시딩이 저장된 `source_text` 와 이 문장을 비교해 낡은 벡터를
 * 가려낸다. 두 곳이 따로 만들면 한쪽만 바뀌는 날 모든 macro 가 매 tick 다시 임베딩된다.
 *
 * 형태는 `name(parameter, parameter)` 한 줄 뒤에 docstring 이다. 이름, parameter 이름, docstring 만 쓰고
 * `source` 는 쓰지 않는다 — Python 구문이 의미 검색의 잡음이 된다.
 */
object MacroEmbeddingText {

    fun compose(name: String, parameterNames: List<String>, docstring: String?): String = buildString {
        append(name).append('(').append(parameterNames.joinToString(", ")).append(')')
        docstring?.trim()?.takeIf { it.isNotEmpty() }?.let { append('\n').append(it) }
    }
}
