package kr.artel.orchestration.tracker.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.tracker.client.TrackerIssueDraft
import org.springframework.stereotype.Component

/**
 * agent 가 `ISSUE` 프레임에 실어 보낸 payload 중 우리가 읽는 부분.
 *
 * 서버는 `issue.detail` 의 구조를 강제하지 않는다. 그래도 여기서 선언된 타입으로 파싱하는 이유는
 * `coding-style.md` Data Shapes 그대로다 — 키 오타가 세 층 아래의 null 이 아니라 파싱 경계에서
 * 드러나야 한다. 모르는 키는 무시하되 **원본은 버리지 않는다**(본문 끝에 그대로 붙는다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentIssueDetail(
    val expected: String? = null,
    val actual: String? = null,
    val steps: List<String>? = null
)

/**
 * 외부 tracker 에 만들 이슈의 제목과 본문을 조립한다.
 *
 * 본문에 원본 런으로 돌아가는 링크를 넣는 것이 요점이다 — 개발자가 ARTEL 을 따로 열지 않고도 무엇을
 * 고쳐야 하는지 읽을 수 있어야 한다.
 *
 * ⚠️ home 의 실제 라우트는 이 저장소에서 확인할 수 없다(소유 범위가 orchestration-server 한 곳이다).
 * 그래서 링크가 틀려도 정보를 잃지 않도록 **project · qaTry · issue id 를 글자로도 함께 적는다.**
 */
@Component
class TrackerIssueBodyWriter(
    private val authProperties: AuthProperties,
    private val objectMapper: ObjectMapper
) {
    fun write(issue: IssueEntity, projectId: Long): TrackerIssueDraft {
        val detail = parseDetail(issue)
        val issueId = requireNotNull(issue.id)
        val runUrl = "${authProperties.frontendOrigin}/projects/$projectId/qa-tries/${issue.qaTryId}"

        val body = buildString {
            appendLine("**심각도(severity)**: `${issue.severity}`")
            appendLine()
            appendLine("### 기대 동작")
            appendLine(detail?.expected?.takeIf { it.isNotBlank() } ?: "_agent가 남기지 않았습니다._")
            appendLine()
            appendLine("### 실제 동작")
            appendLine(detail?.actual?.takeIf { it.isNotBlank() } ?: "_agent가 남기지 않았습니다._")
            appendLine()
            appendLine("### 재현 절차")
            val steps = detail?.steps.orEmpty().filter { it.isNotBlank() }
            if (steps.isEmpty()) {
                appendLine("_agent가 남기지 않았습니다._")
            } else {
                steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
            }
            appendLine()
            appendLine("### 원본 QA 실행")
            appendLine(runUrl)
            // 링크가 깨져도 어느 실행인지 잃지 않도록 id 를 글자로 남긴다.
            appendLine()
            appendLine("`project=$projectId` · `qaTry=${issue.qaTryId}` · `issue=$issueId`")
            appendLine("보고 시각(reportedAt): `${issue.reportedAt}`")
            appendLine()
            appendLine("<details><summary>agent가 보낸 원본 payload</summary>")
            appendLine()
            appendLine("```json")
            appendLine(prettyDetail(issue))
            appendLine("```")
            appendLine()
            appendLine("</details>")
            appendLine()
            appendLine("---")
            append("이 이슈는 ARTEL QA agent가 보고한 결함에서 자동으로 만들어졌습니다.")
        }
        return TrackerIssueDraft(title = "[${issue.severity}] ${issue.title}", body = body)
    }

    /** 파싱이 실패해도 이슈 생성을 막지 않는다 — 아는 문단만 비고 원본 블록은 그대로 남는다. */
    private fun parseDetail(issue: IssueEntity): AgentIssueDetail? =
        try {
            objectMapper.readValue(issue.detail.asString(), AgentIssueDetail::class.java)
        } catch (_: Exception) {
            null
        }

    private fun prettyDetail(issue: IssueEntity): String =
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(objectMapper.readTree(issue.detail.asString()))
        } catch (_: Exception) {
            issue.detail.asString()
        }
}
