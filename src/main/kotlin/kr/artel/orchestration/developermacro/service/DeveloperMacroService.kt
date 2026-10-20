package kr.artel.orchestration.developermacro.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.developermacro.agent.AgentMacroClient
import kr.artel.orchestration.developermacro.agent.MacroCheckOutcome
import kr.artel.orchestration.developermacro.dto.CheckDeveloperMacroRequest
import kr.artel.orchestration.developermacro.dto.DEVELOPER_MACRO_NAME_PATTERN
import kr.artel.orchestration.developermacro.dto.DeveloperMacroAuthor
import kr.artel.orchestration.developermacro.dto.DeveloperMacroCheckResponse
import kr.artel.orchestration.developermacro.dto.DeveloperMacroDetailResponse
import kr.artel.orchestration.developermacro.dto.DeveloperMacroDetailRow
import kr.artel.orchestration.developermacro.dto.DeveloperMacroListResponse
import kr.artel.orchestration.developermacro.dto.DeveloperMacroRejection
import kr.artel.orchestration.developermacro.dto.DeveloperMacroSummaryResponse
import kr.artel.orchestration.developermacro.dto.DeveloperMacroSummaryRow
import kr.artel.orchestration.developermacro.dto.InternalDeveloperMacroListResponse
import kr.artel.orchestration.developermacro.dto.MAX_DEVELOPER_MACRO_DOCSTRING_LENGTH
import kr.artel.orchestration.developermacro.dto.MAX_DEVELOPER_MACRO_NAME_LENGTH
import kr.artel.orchestration.developermacro.dto.PutDeveloperMacroRequest
import kr.artel.orchestration.developermacro.repository.DeveloperMacroRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service

private val DEVELOPER_MACRO_NAME = Regex(DEVELOPER_MACRO_NAME_PATTERN)

/**
 * 개발자 macro 의 조회·검사·등록·삭제(ARTEL-1088). 계약은 `.plan/general/2026-10-15-developer-macro-mcp.md` 다.
 *
 * 공개 경로는 모두 프로젝트 멤버 검사(`ProjectAccessService.requireMember`)를 먼저 한다. 멤버가 아니면 프로젝트가
 * 없는 것처럼 404 다. MCP access JWT 로 들어온 요청도 같다 — MCP 서버가 고른 프로젝트를 이 서버는 모르고, 매 요청
 * 경로의 `projectId` 로 멤버 검사를 할 뿐이다.
 *
 * 검사와 등록은 agent-server `POST /internal/macros/check` 를 거친다. 이 서버는 Python 을 파싱하지 않는다.
 * `from macros import` 로 가져올 수 있는 것은 같은 프로젝트의 다른 developer macro 이고, 그 원문을 이 서버가 모아
 * `imports` 로 넘긴다.
 */
@Service
class DeveloperMacroService(
    private val repository: DeveloperMacroRepository,
    private val projectAccess: ProjectAccessService,
    private val projects: ProjectRepository,
    private val gameBuilds: GameBuildRepository,
    private val agent: AgentMacroClient,
    private val objectMapper: ObjectMapper,
    private val projectChanges: ProjectChangeStreamManager,
) {

    suspend fun list(userId: Long, projectId: Long): DeveloperMacroListResponse {
        projectAccess.requireMember(projectId, userId)
        return DeveloperMacroListResponse(repository.findSummaries(projectId).toList().map { it.toResponse() })
    }

    suspend fun read(userId: Long, projectId: Long, name: String): DeveloperMacroDetailResponse {
        projectAccess.requireMember(projectId, userId)
        return repository.findDetail(projectId, name)?.toResponse() ?: throw developerMacroNotFound()
    }

    /** 검사만 한다. 거절도 200 의 `ok: false` 로 돌려준다. */
    suspend fun check(userId: Long, projectId: Long, request: CheckDeveloperMacroRequest): DeveloperMacroCheckResponse {
        projectAccess.requireMember(projectId, userId)
        requireBuildOfProject(userId, projectId, request.gameBuildId!!.toLong())
        return when (val outcome = checkAgainstProject(projectId, request.name, request.source)) {
            is MacroCheckOutcome.Passed -> DeveloperMacroCheckResponse(
                ok = true,
                parameters = outcome.parameterNames,
                docstring = outcome.docstring,
                rejection = null,
            )
            is MacroCheckOutcome.Rejected -> DeveloperMacroCheckResponse(
                ok = false,
                parameters = null,
                docstring = null,
                rejection = DeveloperMacroRejection(outcome.line, outcome.reason),
            )
        }
    }

    /**
     * 검사를 통과하면 이름으로 넣거나 갱신한다. 거절이면 400 `developer_macro_rejected` 이고 아무것도 쓰지 않는다.
     *
     * 저장하는 정의·parameter·docstring 은 agent-server 가 돌려준 값이다. 사용자가 보낸 것은 원문과 기준 build 뿐이다.
     */
    suspend fun put(
        userId: Long,
        projectId: Long,
        name: String,
        request: PutDeveloperMacroRequest,
    ): DeveloperMacroDetailResponse {
        requireValidName(name)
        projectAccess.requireMember(projectId, userId)
        val gameBuildId = request.gameBuildId!!.toLong()
        requireBuildOfProject(userId, projectId, gameBuildId)
        val passed = when (val outcome = checkAgainstProject(projectId, name, request.source)) {
            is MacroCheckOutcome.Passed -> outcome
            is MacroCheckOutcome.Rejected -> throw DeveloperMacroRejectedException(outcome.line, outcome.reason)
        }
        repository.upsertByName(
            projectId = projectId,
            name = name,
            source = request.source,
            definitionJson = Json.of(objectMapper.writeValueAsString(passed.definition)),
            parameterNames = Json.of(objectMapper.writeValueAsString(passed.parameterNames)),
            docstring = passed.docstring,
            gameBuildId = gameBuildId,
            createdBy = userId,
            updatedBy = userId,
        )
        // 트랜잭션 없이 upsert 한 문장이라 여기가 commit 뒤다.
        projectChanges.notify(projectId, ProjectChangeTopic.MACROS)
        // upsert 직후라 행이 있다. 없으면 그 사이에 누가 지운 것이고, 그때는 지운 쪽이 마지막이다.
        return repository.findDetail(projectId, name)?.toResponse() ?: throw developerMacroNotFound()
    }

    suspend fun delete(userId: Long, projectId: Long, name: String) {
        projectAccess.requireMember(projectId, userId)
        if (repository.deleteByProjectIdAndName(projectId, name) == 0) throw developerMacroNotFound()
        projectChanges.notify(projectId, ProjectChangeTopic.MACROS)
    }

    /**
     * agent-server 엔진이 읽는 프로젝트의 developer macro 전부, 원문까지. 멤버 검사가 없다 — 내부 포트 경로다.
     * 지운 프로젝트는 없는 것으로 본다.
     */
    suspend fun listForAgent(projectId: Long): InternalDeveloperMacroListResponse {
        projects.findById(projectId)?.takeIf { it.deletedAt == null }
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        return InternalDeveloperMacroListResponse(repository.findDetails(projectId).toList().map { it.toResponse() })
    }

    /**
     * 같은 프로젝트의 다른 developer macro 원문을 `imports` 로 실어 검사한다. 지금 검사하는 이름은 뺀다.
     *
     * docstring 폭은 이 서버의 저장 제약이라 agent-server 가 모른다. 통과한 결과라도 그 폭을 넘으면 여기서 거절로
     * 바꾼다 — 그래야 검사(`check`)와 등록(`put`)이 같은 답을 한다.
     */
    private suspend fun checkAgainstProject(projectId: Long, name: String, source: String): MacroCheckOutcome {
        val imports = repository.findSourcesExcept(projectId, name).toList().associate { it.name to it.source }
        val outcome = agent.check(name, source, imports)
        if (outcome is MacroCheckOutcome.Passed && outcome.docstring.length > MAX_DEVELOPER_MACRO_DOCSTRING_LENGTH) {
            return MacroCheckOutcome.Rejected(
                line = null,
                reason = "docstring 은 ${MAX_DEVELOPER_MACRO_DOCSTRING_LENGTH}자 이하여야 합니다.",
            )
        }
        return outcome
    }

    /** 기준 build 가 이 프로젝트의 것인지. 아니면 프로젝트의 다른 build 를 찾을 때와 같은 404 다. */
    private suspend fun requireBuildOfProject(userId: Long, projectId: Long, gameBuildId: Long) {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
    }

    /** 경로의 이름은 DTO 검증을 거치지 않으므로 등록에서 직접 거른다. 조회·삭제는 맞지 않는 이름이 그냥 404 다. */
    private fun requireValidName(name: String) {
        if (name.length > MAX_DEVELOPER_MACRO_NAME_LENGTH || !DEVELOPER_MACRO_NAME.matches(name)) {
            throw BadRequestException("macro 이름은 ${MAX_DEVELOPER_MACRO_NAME_LENGTH}자 이하의 Python identifier 여야 합니다.")
        }
    }

    private fun parameterNamesOf(json: Json): List<String> = objectMapper.readValue(json.asString())

    private fun authorOf(id: Long?, nickname: String?): DeveloperMacroAuthor? =
        if (id != null && nickname != null) DeveloperMacroAuthor(id.toString(), nickname) else null

    private fun DeveloperMacroSummaryRow.toResponse() = DeveloperMacroSummaryResponse(
        name = name,
        parameters = parameterNamesOf(parameterNames),
        docstring = docstring,
        gameBuildId = gameBuildId.toString(),
        buildVersion = buildVersion,
        buildLabel = buildLabel,
        version = version,
        updatedAt = updatedAt,
        updatedBy = authorOf(updatedBy, updatedByNickname),
    )

    private fun DeveloperMacroDetailRow.toResponse() = DeveloperMacroDetailResponse(
        name = name,
        source = source,
        parameters = parameterNamesOf(parameterNames),
        docstring = docstring,
        gameBuildId = gameBuildId.toString(),
        buildVersion = buildVersion,
        buildLabel = buildLabel,
        version = version,
        updatedAt = updatedAt,
        updatedBy = authorOf(updatedBy, updatedByNickname),
    )

    private fun developerMacroNotFound() = NotFoundException("developer macro 를 찾을 수 없습니다.")
}

/**
 * 등록하려던 원문을 agent-server 검사가 거절했다. 줄 번호와 이유를 `fields` 에 싣는다.
 *
 * [reason] 은 agent-server 가 사용자의 원문에 대해 쓴 안내다. 이 서버 내부의 예외 message 가 아니므로 4xx 에 실어도
 * `.agents/docs/error-handling.md` 의 규약에 걸리지 않는다.
 */
class DeveloperMacroRejectedException(private val line: Int?, private val reason: String) :
    BadRequestException(
        message = if (line == null) reason else "${line}번째 줄: $reason",
        code = "developer_macro_rejected",
    ) {
    override val responseFields: Map<String, String>
        get() = buildMap {
            line?.let { put("line", it.toString()) }
            put("reason", reason)
        }
}
