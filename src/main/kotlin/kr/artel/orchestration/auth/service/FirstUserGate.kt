package kr.artel.orchestration.auth.service

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

/**
 * `pg_advisory_xact_lock` 의 키. 다른 advisory lock 과 겹치지 않게 고른 임의의 상수다
 * ("ARTELUSR" 의 ASCII 를 64 비트로 읽은 값).
 */
private const val ACCOUNT_CREATION_LOCK_KEY = 0x415254454C555352L

/**
 * 계정을 만드는 모든 경로가 거쳐 가는 잠금과 "첫 사용자인가" 판정.
 *
 * 첫 사용자는 ADMIN 이 된다. 두 가입이 동시에 와서 둘 다 `app_user` 가 비어 있는 것을 보면 ADMIN 이
 * 둘 생기므로, 판정과 행 삽입을 한 트랜잭션 안에서 이 잠금 뒤에 둔다. 잠금은 트랜잭션이 끝날 때
 * 풀린다(`_xact_`). 그래서 [lockAndCheckFirstUser] 는 반드시 트랜잭션 안에서 불러야 한다 —
 * 트랜잭션 밖에서 부르면 잠금이 그 문장 하나 동안만 잡혀 아무것도 막지 못한다.
 *
 * 비밀번호 가입, ADMIN 의 계정 생성과 비밀번호 초기화와 등급 변경이 이 잠금을 잡는다. 계정 생성은 드문 일이라 직렬화해도
 * 비용이 없다.
 *
 * GitHub 첫 로그인은 잡지 않고 ADMIN 도 되지 않는다. 테스트 스위트의 클래스 수십 개가 `app_user` 를
 * 비우고 GitHub 신원으로 사용자를 만드는데, 그 첫 사용자가 ADMIN 이 되면 "참여하지 않은 사람은 못
 * 본다" 를 확인하는 테스트가 실행 순서에 따라 깨진다. GitHub 만 쓰는 설치는 `docs/platform-role.md` 의
 * SQL 로 ADMIN 을 준다.
 */
@Component
class FirstUserGate(private val databaseClient: DatabaseClient) {

    /** 잠금을 잡고 `app_user` 에 행이 하나도 없는지 답한다. */
    suspend fun lockAndCheckFirstUser(): Boolean {
        lockAccountChanges()
        return !anyUserExists()
    }

    /**
     * 잠금만 잡는다. ADMIN 이 계정을 만들거나 등급을 바꿀 때처럼 첫 사용자 판정이 필요 없는 경로가
     * 쓴다. 등급 변경도 이 잠금을 잡는 이유는 "마지막 ADMIN 을 내리지 못한다" 판정이 두 요청 사이에서
     * 깨지지 않게 하려는 것이다.
     */
    suspend fun lockAccountChanges() {
        databaseClient.sql("SELECT pg_advisory_xact_lock(:key)")
            .bind("key", ACCOUNT_CREATION_LOCK_KEY)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 잠금 없이 읽는다. `GET /api/auth/providers` 처럼 판정이 아니라 안내에 쓰는 자리다. */
    suspend fun anyUserExists(): Boolean =
        databaseClient.sql("SELECT EXISTS (SELECT 1 FROM app_user) AS present")
            .map { row, _ -> row.get("present", java.lang.Boolean::class.java)?.booleanValue() ?: false }
            .one().awaitSingle()
}
