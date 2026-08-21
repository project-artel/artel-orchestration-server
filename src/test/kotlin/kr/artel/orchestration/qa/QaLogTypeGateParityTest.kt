package kr.artel.orchestration.qa

import kr.artel.orchestration.qa.service.TYPES
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles

/**
 * `qa_log.type` 을 지키는 두 게이트가 같은 목록을 들고 있는지 본다.
 *
 * 하나는 `QaLogService.append` 의 `require`, 다른 하나는 `qa_log_type_check` 제약이다. 한쪽만
 * 열면 그 타입은 코틀린을 통과한 뒤 INSERT 에서 죽는다.
 *
 * **이 테스트가 있는 이유는 그 실패를 실제로 겪었기 때문이다(ARTEL-414).** 판독 타입을
 * `TYPES` 에만 더하고 마이그레이션을 빠뜨린 채, 브리지를 목으로 세운 통합 테스트는 초록으로
 * 통과했다 — 목이 DB 에 닿지 않으니 제약을 만날 일이 없었다. 로컬에서 SDK 자리를 흉내 낸
 * 소켓으로 판독을 흘려 본 뒤에야 `qa_log_type_check` 위반이 드러났다.
 *
 * 그래서 단언 대상은 "PULSE 가 있는가"가 아니라 **두 목록이 같은가**다. 다음에 누가 어느 쪽만
 * 열어도 여기서 걸린다.
 */
@ActiveProfiles("test")
@SpringBootTest
class QaLogTypeGateParityTest {

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Test
    fun `코틀린 타입 집합과 qa_log_type_check 가 같은 목록을 든다`() {
        val definition = databaseClient
            .sql(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'qa_log_type_check'
                """.trimIndent()
            )
            .map { row -> row.get(0, String::class.java) }
            .one()
            .block()

        assertThat(definition)
            .describedAs("qa_log_type_check 제약이 없다 — 마이그레이션이 빠졌다")
            .isNotNull()

        // pg_get_constraintdef 는 CHECK (((type)::text = ANY ((ARRAY['LOG'::character varying,
        // ...])::text[]))) 처럼 돌려준다. 캐스트와 괄호를 걷어내고 따옴표 안의 값만 고른다.
        val allowed = Regex("'([A-Z_]+)'")
            .findAll(requireNotNull(definition))
            .map { it.groupValues[1] }
            .toSet()

        assertThat(allowed)
            .describedAs("DB 제약과 QaLogService.TYPES 가 어긋난다")
            .isEqualTo(TYPES)
    }
}
