package kr.artel.orchestration.project.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.dto.CreateProjectRequest
import kr.artel.orchestration.project.dto.DeleteProjectResponse
import kr.artel.orchestration.project.dto.Genre
import kr.artel.orchestration.project.dto.ProjectDetailResponse
import kr.artel.orchestration.project.dto.ProjectPageResponse
import kr.artel.orchestration.project.dto.ProjectSummaryResponse
import kr.artel.orchestration.project.dto.UpdateProjectRequest
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 목록 한 번에 돌려줄 수 있는 최대 개수. 클라이언트가 더 큰 값을 보내도 여기로 잘린다. */
private const val MAX_PAGE_SIZE = 100

/**
 * 프로젝트 접근 규칙이 모이는 곳(코루틴).
 *
 * 사용자와 프로젝트는 M:N이고, project_member에 행이 있다는 것이 곧 접근 권한이다.
 * 모든 조회는 그 조인을 질의 안에서 걸어, 조건을 빠뜨렸을 때 남의 프로젝트가 조용히
 * 새어나가는 일이 생기지 않게 한다.
 *
 * 단건 멤버십(역할) 조회는 공통 모듈 [ProjectAccessService]를 쓴다.
 * 쓰기 원자성은 [TransactionalOperator.executeAndAwait]로 감싼다.
 */
@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val memberRepository: ProjectMemberRepository,
    private val documentRepository: ProjectDocumentRepository,
    private val documentAssembler: ProjectDocumentAssembler,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    /**
     * 프로젝트와 만든 사람의 OWNER 참여를 함께 만든다.
     *
     * 두 행이 한 트랜잭션이어야 한다. 프로젝트만 만들어지고 참여가 빠지면 만든 사람조차
     * 접근할 수 없는, 어디에도 보이지 않는 프로젝트가 남는다.
     */
    suspend fun create(userId: Long, request: CreateProjectRequest): ProjectDetailResponse {
        val now = Instant.now(clock)

        return transactionalOperator.executeAndAwait {
            val project = projectRepository.save(
                ProjectEntity(
                    name = request.name.trim(),
                    description = request.description?.trim()?.ifBlank { null },
                    genre = request.genre.name,
                    createdAt = now,
                    updatedAt = now
                )
            )

            memberRepository.save(
                ProjectMemberEntity(
                    projectId = requireNotNull(project.id),
                    appUserId = userId,
                    role = ProjectRole.OWNER.name,
                    createdAt = now
                )
            )

            project.toDetail(ProjectRole.OWNER, document = null)
        }
    }

    suspend fun list(userId: Long, page: Int, size: Int): ProjectPageResponse {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)

        val projects = projectRepository
            .findPageForMember(userId, safeSize, safePage.toLong() * safeSize)
            .toList()
        val items = summaries(userId, projects)
        val total = projectRepository.countForMember(userId)

        return ProjectPageResponse(
            items = items,
            page = safePage,
            size = safeSize,
            total = total
        )
    }

    /** 접근할 수 없거나 삭제된 프로젝트는 null이다. 컨트롤러가 404로 옮긴다. */
    suspend fun get(userId: Long, projectId: Long): ProjectDetailResponse? {
        val project = projectRepository.findAccessibleById(projectId, userId)
            ?: return null
        return withRoleAndLatestDocument(userId, project)
    }

    suspend fun update(
        userId: Long,
        projectId: Long,
        request: UpdateProjectRequest
    ): ProjectDetailResponse? =
        transactionalOperator.executeAndAwait {
            val project = projectRepository.findAccessibleById(projectId, userId)
                ?: return@executeAndAwait null
            val saved = projectRepository.save(project.applying(request))
            withRoleAndLatestDocument(userId, saved)
        }

    /**
     * OWNER만 삭제할 수 있다.
     *
     * 참여자가 아니면 null(→ 404)로, 참여자지만 OWNER가 아니면 [ProjectAccessDeniedException]
     * (→ 403)으로 갈린다. 이미 프로젝트를 볼 수 있는 사람에게 404를 주는 것은 숨기는 시늉일 뿐이다.
     *
     * 실제 행은 지우지 않고 deleted_at만 채운다. S3의 기획서 원본도 그대로 둔다.
     */
    suspend fun delete(userId: Long, projectId: Long): DeleteProjectResponse? =
        transactionalOperator.executeAndAwait {
            val project = projectRepository.findAccessibleById(projectId, userId)
                ?: return@executeAndAwait null
            val member = projectAccessService.member(projectId, userId)
            if (member?.role != ProjectRole.OWNER.name) {
                throw ProjectAccessDeniedException("프로젝트 삭제는 소유자만 할 수 있습니다.")
            }
            projectRepository.save(project.copy(deletedAt = Instant.now(clock)))
            DeleteProjectResponse(deleted = true, projectId = projectId.toString())
        }

    private suspend fun summaries(
        userId: Long,
        projects: List<ProjectEntity>
    ): List<ProjectSummaryResponse> {
        if (projects.isEmpty()) return emptyList()

        val projectIds = projects.mapNotNull { it.id }

        val roleByProject: Map<Long, String> =
            memberRepository.findByAppUserIdAndProjectIdIn(userId, projectIds)
                .toList()
                .associate { it.projectId to it.role }
        val countByProject = documentRepository.countByProjectIds(projectIds)
            .toList()
            .associate { it.projectId to it.documentCount }
        val latestByProject = documentAssembler.toResponsesByProject(
            documentRepository.findLatestByProjectIds(projectIds).toList()
        )

        return projects.map { project ->
            val id = requireNotNull(project.id)
            ProjectSummaryResponse(
                id = id.toString(),
                name = project.name,
                genre = project.genre.toGenre(),
                description = project.description,
                documentCount = countByProject[id] ?: 0L,
                latestDocument = latestByProject[id],
                myRole = roleByProject[id].toRole(),
                updatedAt = project.updatedAt
            )
        }
    }

    private suspend fun withRoleAndLatestDocument(
        userId: Long,
        project: ProjectEntity
    ): ProjectDetailResponse {
        val projectId = requireNotNull(project.id)

        val resolved = projectAccessService.member(projectId, userId)?.role.toRole()
        val latest = documentRepository.findFirstByProjectIdOrderByVersionDesc(projectId)
        val document = latest?.let { documentAssembler.toResponse(it) }

        return project.toDetail(resolved, document)
    }

    private fun ProjectEntity.applying(request: UpdateProjectRequest) = copy(
        name = request.name?.trim()?.ifBlank { null } ?: name,
        // 빈 문자열은 "설명 지우기"다. null은 "이 필드는 건드리지 않음"이라 여기서 구분한다.
        description = when {
            request.description == null -> description
            request.description.isBlank() -> null
            else -> request.description.trim()
        },
        genre = request.genre?.name ?: genre,
        updatedAt = Instant.now(clock)
    )

    private fun ProjectEntity.toDetail(
        role: ProjectRole,
        document: kr.artel.orchestration.project.dto.ProjectDocumentResponse?
    ) = ProjectDetailResponse(
        id = requireNotNull(id).toString(),
        name = name,
        description = description,
        genre = genre.toGenre(),
        document = document,
        myRole = role,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * 저장된 값이 현재 enum에 없으면 [Genre.OTHER]로 떨어뜨린다. 장르 목록에서 값을 빼는 변경이
     * 기존 프로젝트를 통째로 조회 불가로 만드는 일을 막는다.
     */
    private fun String.toGenre(): Genre =
        Genre.entries.firstOrNull { it.name == this } ?: Genre.OTHER

    private fun String?.toRole(): ProjectRole =
        ProjectRole.entries.firstOrNull { it.name == this } ?: ProjectRole.MEMBER
}

/** 접근은 가능하지만 그 동작에 필요한 역할이 아닐 때. 컨트롤러가 403으로 옮긴다. */
class ProjectAccessDeniedException(message: String) : RuntimeException(message)
