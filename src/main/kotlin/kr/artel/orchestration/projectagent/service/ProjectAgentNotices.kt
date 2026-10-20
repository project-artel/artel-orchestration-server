package kr.artel.orchestration.projectagent.service

import kr.artel.orchestration.projectagent.dto.NoticeKind

/** 화면에 보이는 NOTICE 의 한 문장. 사용자 locale 이 `ko` 가 아니면 영어로 쓴다. */
object ProjectAgentNotices {
    fun text(kind: String, locale: String?): String {
        val korean = locale == null || locale.startsWith("ko")
        return when (kind) {
            NoticeKind.ERROR ->
                if (korean) "응답을 받지 못했습니다. 다시 시도해 주세요." else "No response was received. Please try again."
            NoticeKind.STEP_LIMIT ->
                if (korean) "한 번에 실행할 수 있는 작업 수에 도달해 여기서 멈췄습니다." else "Reached the limit of actions for one request, so I stopped here."
            else ->
                if (korean) "작업을 멈췄습니다." else "Stopped."
        }
    }
}
