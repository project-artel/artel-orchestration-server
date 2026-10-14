package kr.artel.orchestration.gamenote.service

import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.gamenote.dto.GameNoteAuthorResponse
import kr.artel.orchestration.gamenote.dto.GameNotesResponse
import kr.artel.orchestration.gamenote.dto.UpdateGameNotesRequest
import kr.artel.orchestration.gamenote.entity.GenreModule
import kr.artel.orchestration.gamenote.entity.ProjectGameNoteEntity
import kr.artel.orchestration.gamenote.repository.ProjectGameNoteRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 프로젝트별 게임 노트(ARTEL-1061). 저장마다 새 버전을 쌓고, QA 런은 시작하는 순간의 최신 버전을 agent-server 에
 * 보낸다.
 *
 * 읽기와 쓰기 모두 프로젝트 참여자면 된다. 프로젝트 수정(`PATCH /api/projects/{projectId}`)과 같은 규칙이고,
 * 참여자가 아니거나 삭제된 프로젝트는 404 다([ProjectAccessService.requireMember]).
 */
@Service
class GameNoteService(
    private val gameNoteRepository: ProjectGameNoteRepository,
    private val projectAccessService: ProjectAccessService,
    private val appUserRepository: AppUserRepository,
    private val clock: Clock
) {
    suspend fun get(userId: Long, projectId: Long): GameNotesResponse {
        projectAccessService.requireMember(projectId, userId)
        return gameNoteRepository.findLatest(projectId).toResponse()
    }

    /**
     * 새 버전을 저장한다.
     *
     * 순서: [UpdateGameNotesRequest.baseVersion] 이 지금 최신과 다르면 409, 본문과 장르 집합이 최신과 같으면 버전을
     * 올리지 않고 최신을 그대로 돌려준다, 그 밖에는 `baseVersion + 1` 을 넣는다. 최신을 읽은 뒤 넣기 전에 다른 저장이
     * 같은 버전을 넣었으면 PK 가 막아 409 다.
     */
    suspend fun save(userId: Long, projectId: Long, request: UpdateGameNotesRequest): GameNotesResponse {
        projectAccessService.requireMember(projectId, userId)
        val baseVersion = requireNotNull(request.baseVersion)
        val body = normalizeBody(requireNotNull(request.body))
        val genres = canonicalGenres(requireNotNull(request.genres))

        val latest = gameNoteRepository.findLatest(projectId)
        if ((latest?.version ?: 0) != baseVersion) throw GameNotesConflictException()
        if (body == (latest?.body ?: "") && genres == latest?.genreModules().orEmpty()) {
            return latest.toResponse()
        }

        val saved = gameNoteRepository.insertIfAbsent(
            projectId = projectId,
            version = baseVersion + 1,
            body = body,
            genres = genres.map { it.wire }.toTypedArray(),
            createdBy = userId,
            createdAt = Instant.now(clock)
        ) ?: throw GameNotesConflictException()
        return saved.toResponse()
    }

    /** PoC 의 `save` 와 같다. 줄바꿈을 LF 로 맞추고 앞뒤 공백을 지운다. */
    private fun normalizeBody(body: String): String =
        body.replace("\r\n", "\n").trim()

    /** 순서는 뜻이 없어서 [GenreModule] 선언 순서로 맞춘다. 그래야 같은 집합이 같은 값으로 저장되고 비교된다. */
    private fun canonicalGenres(names: List<String>): List<GenreModule> {
        val requested = names.mapNotNull { GenreModule.fromWire(it) }.toSet()
        return GenreModule.entries.filter { it in requested }
    }

    private suspend fun ProjectGameNoteEntity?.toResponse(): GameNotesResponse {
        if (this == null) {
            return GameNotesResponse(version = 0, body = "", genres = emptyList(), updatedAt = null, updatedBy = null)
        }
        // 기획서의 `uploadedBy` 와 같다. 사용자 이름은 부가 정보라 찾지 못해도 노트를 감추지 않는다.
        val displayName = appUserRepository.findById(createdBy)?.displayName ?: "알 수 없는 사용자"
        return GameNotesResponse(
            version = version,
            body = body,
            genres = genreModules(),
            updatedAt = createdAt,
            updatedBy = GameNoteAuthorResponse(id = createdBy.toString(), displayName = displayName)
        )
    }
}

/** 편집을 시작한 뒤 다른 저장이 먼저 들어왔다. 화면은 최신을 다시 읽고 다시 고쳐야 한다. */
class GameNotesConflictException :
    ConflictException("편집을 시작한 뒤 게임 노트가 다시 저장되었습니다. 최신 내용을 다시 불러오세요.", code = "game_notes_conflict")
