package kr.artel.orchestration.project.dto

import kr.artel.orchestration.project.entity.ProjectRole
import java.time.Instant

/**
 * 목록 한 줄. 대시보드 요약에 필요한 필드만 담는다.
 *
 * id는 내부적으로 Long이지만 문자열로 내보낸다. /api/auth/me가 이미 그렇게 하고 있고,
 * 클라이언트는 이 값을 파싱하지 않는 불투명 식별자로 다룬다.
 *
 * @property myRole 요청한 사용자의 역할. 클라이언트가 OWNER 전용 동작을 감출 때 쓴다
 */
data class ProjectSummaryResponse(
    val id: String,
    val name: String,
    val genre: Genre,
    val description: String?,
    val documentCount: Long,
    val latestDocument: ProjectDocumentResponse?,
    val myRole: ProjectRole,
    val updatedAt: Instant
)

/** 목록 응답 봉투. */
data class ProjectPageResponse(
    val items: List<ProjectSummaryResponse>,
    val page: Int,
    val size: Int,
    val total: Long
)

/**
 * 프로젝트 상세.
 *
 * @property document 현재 기획서(최신 버전) 하나. 이력은 문서 목록 API로 따로 조회한다
 * @property myRole 요청한 사용자의 역할. OWNER만 삭제할 수 있다
 */
data class ProjectDetailResponse(
    val id: String,
    val name: String,
    val description: String?,
    val genre: Genre,
    val document: ProjectDocumentResponse?,
    val myRole: ProjectRole,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class DeleteProjectResponse(
    val deleted: Boolean,
    val projectId: String
)
