package kr.artel.orchestration.testcase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.project.service.ProjectAccessService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.r2dbc.core.DatabaseClient

class TestCaseIdentifierFormatTest {
    @Test
    fun `프로젝트 표시 번호를 세 자리 이상으로 유지하고 모르는 번호는 생략한다`() {
        assertThat(formatTestCaseIdentifier("14")).isEqualTo("TC-014")
        assertThat(formatTestCaseIdentifier("1000")).isEqualTo("TC-1000")
        assertThat(formatTestCaseIdentifier("90071992547409931234")).isEqualTo("TC-90071992547409931234")
        assertThat(formatTestCaseIdentifier(null)).isEqualTo("TC-…")
    }

    @Test
    fun `표시 번호 조회 오류는 판정 처리를 중단하지 않도록 빈 결과로 반환한다`(): Unit = runBlocking {
        val database = Mockito.mock(DatabaseClient::class.java)
        Mockito.`when`(database.sql(Mockito.anyString())).thenThrow(IllegalStateException("lookup failed"))
        val identifiers = TestCaseIdentifiers(database, Mockito.mock(ProjectAccessService::class.java))
        assertThat(identifiers.findNumber(2, 20)).isNull()
    }

    @Test
    fun `표시 번호 조회 중 cancellation은 전파한다`() {
        val database = Mockito.mock(DatabaseClient::class.java)
        Mockito.`when`(database.sql(Mockito.anyString())).thenThrow(CancellationException("cancelled"))
        val identifiers = TestCaseIdentifiers(database, Mockito.mock(ProjectAccessService::class.java))
        assertThatThrownBy { runBlocking { identifiers.findNumber(2, 20) } }.isInstanceOf(CancellationException::class.java)
    }
}
