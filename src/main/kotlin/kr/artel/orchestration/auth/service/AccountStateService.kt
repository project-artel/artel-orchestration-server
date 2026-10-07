package kr.artel.orchestration.auth.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service

/** 인증은 됐지만 요청을 받을 수 없는 계정 상태. */
enum class AccountBlock(val code: String, val message: String) {
    /** ADMIN 이 막은 계정. */
    DISABLED("account_disabled", "비활성화된 계정입니다."),

    /** 임시 비밀번호로 들어온 계정. 비밀번호를 바꾸기 전까지는 프로필 조회와 비밀번호 변경만 된다. */
    PASSWORD_CHANGE_REQUIRED("password_change_required", "비밀번호를 먼저 바꿔야 합니다.")
}

/**
 * 인증된 요청마다 계정이 막혔는지 DB 에서 읽는다. `AccountStateWebFilter` 가 부른다.
 *
 * 토큰에 싣지 않는 이유는 `PlatformAccessService` 가 등급을 DB 에서 읽는 이유와 같다. 토큰에 실으면
 * ADMIN 이 계정을 막아도, 사용자가 비밀번호를 바꿔도 access 토큰이 만료될 때까지(15분) 옛 상태가
 * 통한다. 대가는 인증된 요청마다 primary key 조회 하나다.
 */
@Service
class AccountStateService(private val databaseClient: DatabaseClient) {

    /** 막힌 이유. 막히지 않았거나 사용자 행이 없으면 null 이다(없는 사용자는 다른 자리가 401 로 답한다). */
    suspend fun blockOf(userId: Long): AccountBlock? =
        databaseClient.sql(
            """
            SELECT u.disabled, COALESCE(c.must_change_password, FALSE) AS must_change_password
              FROM app_user u
              LEFT JOIN local_credential c ON c.app_user_id = u.id
             WHERE u.id = :userId
            """
        )
            .bind("userId", userId)
            .map { row, _ ->
                AccountStateRow(
                    disabled = row.get("disabled", java.lang.Boolean::class.java)?.booleanValue() == true,
                    mustChangePassword =
                        row.get("must_change_password", java.lang.Boolean::class.java)?.booleanValue() == true
                )
            }
            .one()
            .awaitSingleOrNull()
            ?.block
}

private data class AccountStateRow(val disabled: Boolean, val mustChangePassword: Boolean) {
    /** 둘 다면 DISABLED 가 이긴다. 비밀번호를 바꿔도 막힌 계정은 풀리지 않는다. */
    val block: AccountBlock?
        get() = when {
            disabled -> AccountBlock.DISABLED
            mustChangePassword -> AccountBlock.PASSWORD_CHANGE_REQUIRED
            else -> null
        }
}
