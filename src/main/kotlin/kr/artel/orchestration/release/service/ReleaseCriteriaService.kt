package kr.artel.orchestration.release.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.release.dto.ReleaseCriteria
import kr.artel.orchestration.release.dto.ReleaseCriteriaResponse
import kr.artel.orchestration.release.repository.ReleaseCriteriaRepository
import org.springframework.stereotype.Service

/** 프로젝트의 출시 기준 숫자(ARTEL-1386). 참여자는 읽고, OWNER 만 바꾼다. */
@Service
class ReleaseCriteriaService(
    private val repository: ReleaseCriteriaRepository,
    private val access: ProjectAccessService
) {
    suspend fun get(projectId: Long, appUserId: Long): ReleaseCriteriaResponse {
        access.requireMember(projectId, appUserId)
        return response(projectId)
    }

    suspend fun update(projectId: Long, appUserId: Long, criteria: ReleaseCriteria): ReleaseCriteriaResponse {
        access.requireOwner(projectId, appUserId, "출시 기준은 프로젝트 OWNER 만 바꿀 수 있습니다.")
        validate(criteria)
        repository.save(projectId, criteria, appUserId)
        return response(projectId)
    }

    suspend fun reset(projectId: Long, appUserId: Long): ReleaseCriteriaResponse {
        access.requireOwner(projectId, appUserId, "출시 기준은 프로젝트 OWNER 만 바꿀 수 있습니다.")
        repository.delete(projectId)
        return response(projectId)
    }

    private suspend fun response(projectId: Long): ReleaseCriteriaResponse {
        val stored = repository.find(projectId)
        return ReleaseCriteriaResponse(
            criteria = stored?.criteria ?: ReleaseCriteria.DEFAULT,
            isDefault = stored == null,
            defaults = ReleaseCriteria.DEFAULT,
            updatedAt = stored?.updatedAt
        )
    }

    private fun validate(criteria: ReleaseCriteria) {
        val counts = mapOf("maxPending" to criteria.maxPending, "maxRegression" to criteria.maxRegression, "maxOpenBlockers" to criteria.maxOpenBlockers)
        val rates = mapOf("minPassRate" to criteria.minPassRate, "maxInconclusiveRate" to criteria.maxInconclusiveRate)
        counts.forEach { (name, value) -> if (value !in 0..MAX_COUNT) throw BadRequestException("$name must be between 0 and $MAX_COUNT") }
        rates.forEach { (name, value) -> if (value !in 0..100) throw BadRequestException("$name must be between 0 and 100") }
    }

    private companion object {
        const val MAX_COUNT = 100_000
    }
}
