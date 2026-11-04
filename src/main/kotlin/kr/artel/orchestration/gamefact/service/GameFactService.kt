package kr.artel.orchestration.gamefact.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.gamefact.dto.GameFactListResponse
import kr.artel.orchestration.gamefact.dto.GameFactResponse
import kr.artel.orchestration.gamefact.dto.GameFactRow
import kr.artel.orchestration.gamefact.dto.GameRuleResponse
import kr.artel.orchestration.gamefact.dto.GameRuleRow
import kr.artel.orchestration.gamefact.dto.InternalGameFactListResponse
import kr.artel.orchestration.gamefact.dto.InternalGameFactResponse
import kr.artel.orchestration.gamefact.dto.InternalGameRuleResponse
import kr.artel.orchestration.gamefact.dto.PutGameFactRequest
import kr.artel.orchestration.gamefact.dto.PutGameRuleRequest
import kr.artel.orchestration.gamefact.repository.GameFactRepository
import kr.artel.orchestration.gamefact.repository.GameRuleRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service

/**
 * fact·rule 의 조회·등록·삭제(V177).
 *
 * 공개 경로는 모두 프로젝트 멤버 검사를 먼저 한다. 멤버가 아니면 프로젝트가 없는 것처럼 404 다(`GameIdentityService` 와
 * 같은 규칙). 칸의 모양은 DTO 검증이 보고, 이 서비스는 rule 과 fact 의 짝을 본다 — rule 이 가리키는 fact 는 같은 프로젝트에
 * 있어야 하고, rule 이 쓰는 fact 는 지우지 못한다.
 */
@Service
class GameFactService(
    private val facts: GameFactRepository,
    private val rules: GameRuleRepository,
    private val projectAccess: ProjectAccessService,
    private val projects: ProjectRepository,
    private val gameBuilds: GameBuildRepository,
) {

    suspend fun list(userId: Long, projectId: Long): GameFactListResponse {
        projectAccess.requireMember(projectId, userId)
        return GameFactListResponse(
            facts = facts.findRows(projectId).toList().map { it.toResponse() },
            rules = rules.findRows(projectId).toList().map { it.toResponse() },
        )
    }

    suspend fun putFact(userId: Long, projectId: Long, request: PutGameFactRequest): GameFactResponse {
        projectAccess.requireMember(projectId, userId)
        val gameBuildId = requireBuildOfProject(userId, projectId, request.gameBuildId!!)
        return facts.upsertByName(
            projectId = projectId,
            name = request.name!!,
            scope = request.scope!!,
            member = request.member!!,
            type = request.type!!,
            unit = request.unit?.takeIf { it.isNotBlank() },
            means = request.means!!,
            gameBuildId = gameBuildId,
            createdBy = userId,
            updatedBy = userId,
        ).toResponse()
    }

    /** 지운다. 이 fact 를 말하는 rule 이 있으면 그 이름을 담아 409 다 — rule 을 먼저 고치거나 지워야 한다. */
    suspend fun deleteFact(userId: Long, projectId: Long, name: String) {
        projectAccess.requireMember(projectId, userId)
        val using = rules.findNamesUsingFact(projectId, name).toList()
        if (using.isNotEmpty()) {
            throw ConflictException(
                "fact `$name` 을 말하는 rule 이 있어 지울 수 없습니다: ${using.joinToString(", ")}",
                code = "game_fact_in_use",
            )
        }
        if (facts.deleteByProjectIdAndName(projectId, name) == 0) {
            throw NotFoundException("fact 를 찾을 수 없습니다.")
        }
    }

    /** 이름으로 넣거나 갱신한다. 가리키는 fact 가 프로젝트에 없으면 그 이름을 담아 400 이다. */
    suspend fun putRule(userId: Long, projectId: Long, request: PutGameRuleRequest): GameRuleResponse {
        projectAccess.requireMember(projectId, userId)
        val gameBuildId = requireBuildOfProject(userId, projectId, request.gameBuildId!!)
        val named = request.facts!!.map { it!! }.distinct()
        val existing = facts.findExistingNames(projectId, named.toTypedArray()).toList().toSet()
        val missing = named.filterNot { it in existing }
        if (missing.isNotEmpty()) {
            throw BadRequestException(
                "rule 이 가리키는 fact 가 이 프로젝트에 없습니다: ${missing.joinToString(", ")}",
                code = "game_rule_unknown_fact",
            )
        }
        return rules.upsertByName(
            projectId = projectId,
            name = request.name!!,
            says = request.says!!,
            facts = named.toTypedArray(),
            gameBuildId = gameBuildId,
            createdBy = userId,
            updatedBy = userId,
        ).toResponse()
    }

    suspend fun deleteRule(userId: Long, projectId: Long, name: String) {
        projectAccess.requireMember(projectId, userId)
        if (rules.deleteByProjectIdAndName(projectId, name) == 0) {
            throw NotFoundException("rule 을 찾을 수 없습니다.")
        }
    }

    /**
     * agent-server 엔진이 런 시작 때 읽는 프로젝트의 fact·rule 전부. 멤버 검사가 없다 — 내부 포트 경로다. 지운 프로젝트는
     * 없는 것으로 본다(`GameIdentityService.listForAgent` 와 같다).
     */
    suspend fun listForAgent(projectId: Long): InternalGameFactListResponse {
        projects.findById(projectId)?.takeIf { it.deletedAt == null }
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        return InternalGameFactListResponse(
            facts = facts.findRows(projectId).toList().map {
                InternalGameFactResponse(it.name, it.scope, it.member, it.type, it.unit, it.means, it.version)
            },
            rules = rules.findRows(projectId).toList().map {
                InternalGameRuleResponse(it.name, it.says, it.facts.toList(), it.version)
            },
        )
    }

    /** 기준 build 가 이 프로젝트의 것인지. 아니면 developer macro 등록과 같은 404 다. */
    private suspend fun requireBuildOfProject(userId: Long, projectId: Long, wireId: String): Long {
        val gameBuildId = wireId.toLong()
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
        return gameBuildId
    }

    private fun GameFactRow.toResponse() = GameFactResponse(
        id = id.toString(),
        name = name,
        scope = scope,
        member = member,
        type = type,
        unit = unit,
        means = means,
        gameBuildId = gameBuildId.toString(),
        version = version,
        updatedAt = updatedAt,
    )

    private fun GameRuleRow.toResponse() = GameRuleResponse(
        id = id.toString(),
        name = name,
        says = says,
        facts = facts.toList(),
        gameBuildId = gameBuildId.toString(),
        version = version,
        updatedAt = updatedAt,
    )
}
