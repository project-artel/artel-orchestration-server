package kr.artel.orchestration.project.service

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
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant

/** 목록 한 번에 돌려줄 수 있는 최대 개수. 클라이언트가 더 큰 값을 보내도 여기로 잘린다. */
private const val MAX_PAGE_SIZE = 100

/**
 * 프로젝트 접근 규칙이 모이는 곳.
 *
 * 사용자와 프로젝트는 M:N이고, project_member에 행이 있다는 것이 곧 접근 권한이다.
 * 모든 조회는 그 조인을 질의 안에서 걸어, 조건을 빠뜨렸을 때 남의 프로젝트가 조용히
 * 새어나가는 일이 생기지 않게 한다.
 */
@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val memberRepository: ProjectMemberRepository,
    private val documentRepository: ProjectDocumentRepository,
    private val documentAssembler: ProjectDocumentAssembler,
    private val clock: Clock
) {
    /**
     * 프로젝트와 만든 사람의 OWNER 참여를 함께 만든다.
     *
     * 두 행이 한 트랜잭션이어야 한다. 프로젝트만 만들어지고 참여가 빠지면 만든 사람조차
     * 접근할 수 없는, 어디에도 보이지 않는 프로젝트가 남는다.
     */
    @Transactional
    fun create(userId: Long, request: CreateProjectRequest): Mono<ProjectDetailResponse> {
        val now = Instant.now(clock)

        return projectRepository.save(
            ProjectEntity(
                name = request.name.trim(),
                description = request.description?.trim()?.ifBlank { null },
                genre = request.genre.name,
                createdAt = now,
                updatedAt = now
            )
        ).flatMap { project ->
            memberRepository.save(
                ProjectMemberEntity(
                    projectId = requireNotNull(project.id),
                    appUserId = userId,
                    role = ProjectRole.OWNER.name,
                    createdAt = now
                )
            ).thenReturn(project)
        }.map { it.toDetail(ProjectRole.OWNER, document = null) }
    }

    @Transactional(readOnly = true)
    fun list(userId: Long, page: Int, size: Int): Mono<ProjectPageResponse> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)

        return projectRepository
            .findPageForMember(userId, safeSize, safePage.toLong() * safeSize)
            .collectList()
            .flatMap { projects ->
                summaries(userId, projects).flatMap { items ->
                    projectRepository.countForMember(userId).map { total ->
                        ProjectPageResponse(
                            items = items,
                            page = safePage,
                            size = safeSize,
                            total = total
                        )
                    }
                }
            }
    }

    /** 접근할 수 없거나 삭제된 프로젝트는 빈 Mono다. 컨트롤러가 404로 옮긴다. */
    @Transactional(readOnly = true)
    fun get(userId: Long, projectId: Long): Mono<ProjectDetailResponse> =
        projectRepository.findAccessibleById(projectId, userId)
            .flatMap { project -> withRoleAndLatestDocument(userId, project) }

    @Transactional
    fun update(
        userId: Long,
        projectId: Long,
        request: UpdateProjectRequest
    ): Mono<ProjectDetailResponse> =
        projectRepository.findAccessibleById(projectId, userId)
            .flatMap { project ->
                projectRepository.save(project.applying(request))
            }
            .flatMap { project -> withRoleAndLatestDocument(userId, project) }

    /**
     * OWNER만 삭제할 수 있다.
     *
     * 참여자가 아니면 빈 Mono(→ 404)로, 참여자지만 OWNER가 아니면 [ProjectAccessDeniedException]
     * (→ 403)으로 갈린다. 이미 프로젝트를 볼 수 있는 사람에게 404를 주는 것은 숨기는 시늉일 뿐이다.
     *
     * 실제 행은 지우지 않고 deleted_at만 채운다. S3의 기획서 원본도 그대로 둔다.
     */
    @Transactional
    fun delete(userId: Long, projectId: Long): Mono<DeleteProjectResponse> =
        projectRepository.findAccessibleById(projectId, userId)
            .flatMap { project ->
                memberRepository.findByProjectIdAndAppUserId(projectId, userId)
                    .flatMap { member ->
                        if (member.role != ProjectRole.OWNER.name) {
                            Mono.error(ProjectAccessDeniedException("프로젝트 삭제는 소유자만 할 수 있습니다."))
                        } else {
                            projectRepository.save(project.copy(deletedAt = Instant.now(clock)))
                        }
                    }
            }
            .map { DeleteProjectResponse(deleted = true, projectId = projectId.toString()) }

    private fun summaries(
        userId: Long,
        projects: List<ProjectEntity>
    ): Mono<List<ProjectSummaryResponse>> {
        if (projects.isEmpty()) return Mono.just(emptyList())

        val projectIds = projects.mapNotNull { it.id }

        val roles: Mono<Map<Long, String>> =
            memberRepository.findByAppUserIdAndProjectIdIn(userId, projectIds)
                .collectMap({ it.projectId }, { it.role })
        val counts = documentRepository.countByProjectIds(projectIds)
            .collectMap({ it.projectId }, { it.documentCount })
        val latest = documentRepository.findLatestByProjectIds(projectIds)
            .collectList()
            .flatMap(documentAssembler::toResponsesByProject)

        return Mono.zip(roles, counts, latest).map { combined ->
            val roleByProject = combined.t1
            val countByProject = combined.t2
            val latestByProject = combined.t3

            projects.map { project ->
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
    }

    private fun withRoleAndLatestDocument(
        userId: Long,
        project: ProjectEntity
    ): Mono<ProjectDetailResponse> {
        val projectId = requireNotNull(project.id)

        val role = memberRepository.findByProjectIdAndAppUserId(projectId, userId)
            .map { it.role.toRole() }
            .defaultIfEmpty(ProjectRole.MEMBER)

        val latest = documentRepository.findFirstByProjectIdOrderByVersionDesc(projectId)
            .flatMap(documentAssembler::toResponse)

        return role.flatMap { resolved ->
            latest.map { project.toDetail(resolved, it) }
                .switchIfEmpty(Mono.fromCallable { project.toDetail(resolved, null) })
        }
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
