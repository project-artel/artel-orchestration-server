package kr.artel.orchestration.admin.controller

import kr.artel.orchestration.admin.dto.AdminUserResponse
import kr.artel.orchestration.admin.dto.CreateUserRequest
import kr.artel.orchestration.admin.dto.UpdateUserRequest
import kr.artel.orchestration.admin.dto.UserWithTemporaryPasswordResponse
import kr.artel.orchestration.admin.service.AdminUserService
import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * ADMIN 의 사용자 관리. 모든 경로가 먼저 `PlatformAccessService.requireAdmin` 을 지난다 — USER 와
 * DEVELOPER 는 403 `admin_required` 다.
 */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(
    private val adminUserService: AdminUserService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun listUsers(@CurrentUserId userId: Long): List<AdminUserResponse> {
        platformAccessService.requireAdmin(userId)
        return adminUserService.list()
    }

    /** 계정을 만든다. 임시 비밀번호는 이 201 응답에만 실린다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createUser(
        @CurrentUserId userId: Long,
        @RequestBody request: CreateUserRequest
    ): UserWithTemporaryPasswordResponse {
        platformAccessService.requireAdmin(userId)
        return adminUserService.create(userId, request.email, request.name, request.platformRole)
    }

    /** 비밀번호를 새 임시 비밀번호로 바꾼다. 그 사람은 다음 로그인 뒤 비밀번호를 바꿔야 한다. */
    @PostMapping("/{targetUserId}/reset-password")
    suspend fun resetUserPassword(
        @CurrentUserId userId: Long,
        @PathVariable targetUserId: Long
    ): UserWithTemporaryPasswordResponse {
        platformAccessService.requireAdmin(userId)
        return adminUserService.resetPassword(userId, targetUserId)
    }

    @PatchMapping("/{targetUserId}")
    suspend fun updateUser(
        @CurrentUserId userId: Long,
        @PathVariable targetUserId: Long,
        @RequestBody request: UpdateUserRequest
    ): AdminUserResponse {
        platformAccessService.requireAdmin(userId)
        return adminUserService.update(userId, targetUserId, request.platformRole, request.disabled)
    }
}
