package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.AppUserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUserEntity, Long>
