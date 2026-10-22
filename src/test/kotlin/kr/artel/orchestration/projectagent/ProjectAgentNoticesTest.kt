package kr.artel.orchestration.projectagent

import kr.artel.orchestration.projectagent.dto.NoticeKind
import kr.artel.orchestration.projectagent.dto.ProjectAgentFailureCause
import kr.artel.orchestration.projectagent.service.ProjectAgentNotices
import kr.artel.orchestration.userllmkey.entity.KeySource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProjectAgentNoticesTest {

    @Test
    fun `원인마다 서로 다른 문장을 쓰고 원인이 없으면 이전 문장 그대로다`() {
        for (locale in listOf("ko", "en")) {
            val texts = ProjectAgentFailureCause.ALL.map {
                ProjectAgentNotices.errorText(it, KeySource.PLATFORM, "claude/claude-opus-5-5", locale)
            }
            assertThat(texts).describedAs(locale).doesNotHaveDuplicates()
            assertThat(texts).describedAs(locale).allSatisfy { assertThat(it).contains("claude/claude-opus-5-5") }
            assertThat(ProjectAgentNotices.errorText(null, null, null, locale))
                .isEqualTo(ProjectAgentNotices.text(NoticeKind.ERROR, locale))
        }
        assertThat(ProjectAgentNotices.text(NoticeKind.ERROR, "ko")).isEqualTo("응답을 받지 못했습니다. 다시 시도해 주세요.")
    }

    @Test
    fun `key 원인은 사용자 key 면 계정 설정을, 공용 key 면 관리자를 가리킨다`() {
        for (cause in listOf(ProjectAgentFailureCause.LLM_KEY_MISSING, ProjectAgentFailureCause.LLM_AUTH_FAILED)) {
            assertThat(ProjectAgentNotices.errorText(cause, KeySource.USER, null, "ko")).contains("계정 설정")
            assertThat(ProjectAgentNotices.errorText(cause, KeySource.PLATFORM, null, "ko")).contains("관리자")
            assertThat(ProjectAgentNotices.errorText(cause, KeySource.USER, null, "en")).contains("account settings")
            assertThat(ProjectAgentNotices.errorText(cause, KeySource.PLATFORM, null, "en")).contains("administrator")
        }
    }
}
