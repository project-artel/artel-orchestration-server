package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.AppUserEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface AppUserRepository : CoroutineCrudRepository<AppUserEntity, Long>
