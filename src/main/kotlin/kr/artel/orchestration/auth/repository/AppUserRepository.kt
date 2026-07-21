package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.AppUserEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository

interface AppUserRepository : ReactiveCrudRepository<AppUserEntity, Long>
