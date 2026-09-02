package kr.artel.orchestration.auth.service

/**
 * 사용자를 가리키는 표준 문자열 `nickname#userTag` — 화면에 이름을 그릴 때도, 사람을 찾을 때도
 * 같은 형식을 쓴다.
 *
 * API 는 `nickname` 과 `userTag` 를 따로 실어 보낸다(계정 화면이 nickname 만 고치기 때문이다).
 * 붙이고 가르는 일은 호출부마다 문자열을 만지지 않도록 여기 한 곳에서만 한다.
 */
data class UserHandle(val nickname: String, val userTag: String) {

    override fun toString(): String = format(nickname, userTag)

    companion object {
        private const val SEPARATOR = '#'

        fun format(nickname: String, userTag: String): String = "$nickname$SEPARATOR$userTag"

        /**
         * `nickname#userTag` 를 두 조각으로 가른다. 형식이 아니면 null 이다.
         *
         * **마지막 `#` 에서 가른다.** nickname 에는 `#` 이 들어갈 수 있어서 첫 `#` 으로 가르면
         * `a#b#0001` 의 이름이 `a` 로 잘린다. user_tag 는 숫자뿐이라 `#` 을 담지 않으므로 마지막
         * `#` 이 항상 구분자다.
         */
        fun parse(value: String): UserHandle? {
            val separatorIndex = value.lastIndexOf(SEPARATOR)
            if (separatorIndex <= 0) return null
            val nickname = value.substring(0, separatorIndex)
            val userTag = value.substring(separatorIndex + 1)
            if (userTag.isEmpty() || !userTag.all(Char::isDigit)) return null
            return UserHandle(nickname, userTag)
        }
    }
}
