package kr.artel.orchestration.project.service

import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.dto.DocumentUploaderResponse
import kr.artel.orchestration.project.dto.ProjectDocumentResponse
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 기획서 엔티티를 응답으로 옮긴다. 업로더 이름이 필요해 사용자 조회가 끼므로,
 * 한 건씩 조회하지 않도록 여러 건을 한 번에 처리한다.
 */
@Component
class ProjectDocumentAssembler(
    private val appUserRepository: AppUserRepository
) {
    fun toResponse(document: ProjectDocumentEntity): Mono<ProjectDocumentResponse> =
        toResponses(listOf(document)).mapNotNull { it.firstOrNull() }

    /** 준 순서를 그대로 유지한 응답 목록. 한 프로젝트의 여러 버전을 옮길 때 쓴다. */
    fun toResponses(
        documents: List<ProjectDocumentEntity>
    ): Mono<List<ProjectDocumentResponse>> {
        if (documents.isEmpty()) return Mono.just(emptyList())

        return uploaderNames(documents).map { names ->
            documents.map { it.toResponse(names[it.uploadedBy]) }
        }
    }

    /** 프로젝트 id별 응답. 목록 화면에서 프로젝트마다 최신 기획서를 붙일 때 쓴다. */
    fun toResponsesByProject(
        documents: Collection<ProjectDocumentEntity>
    ): Mono<Map<Long, ProjectDocumentResponse>> {
        if (documents.isEmpty()) return Mono.just(emptyMap())

        return uploaderNames(documents).map { names ->
            documents.associate { it.projectId to it.toResponse(names[it.uploadedBy]) }
        }
    }

    private fun uploaderNames(
        documents: Collection<ProjectDocumentEntity>
    ): Mono<Map<Long, String>> =
        appUserRepository.findAllById(documents.map { it.uploadedBy }.distinct())
            .collectMap({ requireNotNull(it.id) }, { it.displayName })

    /**
     * 업로더를 찾지 못해도 문서를 감추지 않는다. 사용자 표시 이름은 부가 정보일 뿐이라,
     * 그것 때문에 기획서 자체가 목록에서 사라지는 편이 훨씬 나쁘다.
     */
    private fun ProjectDocumentEntity.toResponse(uploaderName: String?) = ProjectDocumentResponse(
        id = requireNotNull(id).toString(),
        version = version,
        fileName = fileName,
        contentType = contentType,
        sizeBytes = sizeBytes,
        uploadedAt = uploadedAt,
        uploadedBy = DocumentUploaderResponse(
            id = uploadedBy.toString(),
            displayName = uploaderName ?: "알 수 없는 사용자"
        ),
        parseStatus = parseStatus
    )
}
