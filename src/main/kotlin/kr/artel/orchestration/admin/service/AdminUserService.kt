package kr.artel.orchestration.admin.service

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.admin.dto.AdminUserResponse
import kr.artel.orchestration.admin.dto.UserWithTemporaryPasswordResponse
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AccountNotFoundException
import kr.artel.orchestration.auth.service.FirstUserGate
import kr.artel.orchestration.auth.service.PasswordAccountService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import io.r2dbc.spi.Row
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime

/** 관리 화면 목록 한 줄을 읽는 질의. `oauth_identity` 를 묶어 제공자 이름을 쉼표로 붙인다. */
private const val ADMIN_USER_SELECT = """
    SELECT u.id, u.display_name, u.nickname, u.user_tag, u.email, u.platform_role, u.disabled, u.created_at,
           c.email AS login_email,
           COALESCE(c.must_change_password, FALSE) AS must_change_password,
           (c.app_user_id IS NOT NULL) AS has_password,
           (SELECT string_agg(DISTINCT i.provider, ',') FROM oauth_identity i WHERE i.app_user_id = u.id)
               AS oauth_providers
      FROM app_user u
      LEFT JOIN local_credential c ON c.app_user_id = u.id
"""

/**
 * ADMIN 의 사용자 관리. 호출부(`AdminUserController`)가 ADMIN 인지 먼저 확인한다.
 *
 * 마지막 ADMIN 을 내리거나 막는 요청은 409 다. 그것을 허락하면 설치에 관리자가 없어지고, 되돌리는
 * 길이 DB 를 직접 고치는 것뿐이다.
 */
@Service
class AdminUserService(
    private val appUserRepository: AppUserRepository,
    private val passwordAccountService: PasswordAccountService,
    private val firstUserGate: FirstUserGate,
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(AdminUserService::class.java)

    /** 사용자 전부, 가입 순. 직접 설치한 서버의 사용자 수는 작아서 나누어 읽지 않는다. */
    suspend fun list(): List<AdminUserResponse> =
        databaseClient.sql("$ADMIN_USER_SELECT ORDER BY u.id")
            .map { row, _ -> toResponse(row) }
            .all().collectList().awaitSingleOrNull().orEmpty()

    suspend fun find(userId: Long): AdminUserResponse =
        databaseClient.sql("$ADMIN_USER_SELECT WHERE u.id = :userId")
            .bind("userId", userId)
            .map { row, _ -> toResponse(row) }
            .one().awaitSingleOrNull() ?: throw AccountNotFoundException()

    suspend fun create(adminUserId: Long, email: String, name: String, platformRole: String?): UserWithTemporaryPasswordResponse {
        val role = parseRole(platformRole ?: PlatformRole.USER.name)
        val created = passwordAccountService.createByAdmin(email, name, role)
        val userId = requireNotNull(created.user.id)
        logger.info("app_user {} created app_user {} with role {}", adminUserId, userId, role)
        return UserWithTemporaryPasswordResponse(find(userId), requireNotNull(created.temporaryPassword))
    }

    suspend fun resetPassword(adminUserId: Long, userId: Long): UserWithTemporaryPasswordResponse {
        val temporaryPassword = passwordAccountService.resetByAdmin(userId)
        logger.info("app_user {} reset the password of app_user {}", adminUserId, userId)
        return UserWithTemporaryPasswordResponse(find(userId), temporaryPassword)
    }

    suspend fun update(adminUserId: Long, userId: Long, platformRole: String?, disabled: Boolean?): AdminUserResponse {
        val role = platformRole?.let(::parseRole)
        transactionalOperator.executeAndAwait {
            firstUserGate.lockAccountChanges()
            val user = appUserRepository.findById(userId) ?: throw AccountNotFoundException()
            val updated = user.copy(
                platformRole = role?.name ?: user.platformRole,
                disabled = disabled ?: user.disabled,
                updatedAt = Instant.now(clock)
            )
            val wasActiveAdmin = user.platformRole == PlatformRole.ADMIN.name && !user.disabled
            val staysActiveAdmin = updated.platformRole == PlatformRole.ADMIN.name && !updated.disabled
            if (wasActiveAdmin && !staysActiveAdmin && appUserRepository.countActiveAdmins() <= 1) {
                throw LastAdminException()
            }
            appUserRepository.save(updated)
        }
        logger.info("app_user {} updated app_user {} (role={}, disabled={})", adminUserId, userId, role, disabled)
        return find(userId)
    }

    private fun parseRole(value: String): PlatformRole =
        PlatformRole.parseOrNull(value)
            ?: throw BadRequestException("platformRole 은 USER, DEVELOPER, ADMIN 중 하나여야 합니다.", code = "invalid_platform_role")

    private fun toResponse(row: Row) = AdminUserResponse(
        id = row.get("id", java.lang.Long::class.java).toString(),
        displayName = row.get("display_name", String::class.java)!!,
        nickname = row.get("nickname", String::class.java)!!,
        userTag = row.get("user_tag", String::class.java)!!,
        email = row.get("email", String::class.java),
        loginEmail = row.get("login_email", String::class.java),
        platformRole = row.get("platform_role", String::class.java)!!,
        disabled = row.get("disabled", java.lang.Boolean::class.java)?.booleanValue() == true,
        hasPassword = row.get("has_password", java.lang.Boolean::class.java)?.booleanValue() == true,
        mustChangePassword = row.get("must_change_password", java.lang.Boolean::class.java)?.booleanValue() == true,
        oauthProviders = row.get("oauth_providers", String::class.java)?.split(',').orEmpty(),
        createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant()
    )
}

/** 남은 하나뿐인 ADMIN 을 내리거나 막으려 할 때. */
class LastAdminException :
    ConflictException("마지막 관리자는 내리거나 막을 수 없습니다. 다른 사람을 먼저 ADMIN 으로 올리세요.", code = "last_admin")
