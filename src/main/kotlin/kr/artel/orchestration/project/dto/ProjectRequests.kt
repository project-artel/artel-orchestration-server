package kr.artel.orchestration.project.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 프로젝트 생성 요청.
 *
 * @property name 프로젝트 이름
 * @property description 설명. 없으면 null
 * @property genre 장르
 */
data class CreateProjectRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,

    @field:Size(max = 2000)
    val description: String? = null,

    @field:NotNull
    val genre: Genre
)

/**
 * 프로젝트 수정 요청. 부분 수정이라 null인 필드는 손대지 않는다.
 *
 * 설명을 지울 때는 null이 아니라 빈 문자열을 보낸다. Jackson은 "키가 없음"과 "값이 null"을
 * 같은 null로 만들기 때문에, null에 "지우기" 의미를 주면 나머지 필드만 수정하려는 요청이
 * 설명까지 지워버린다. 빈 문자열은 저장 시 null로 정규화한다.
 *
 * @property name 새 이름. null이면 유지
 * @property description 새 설명. null이면 유지, 빈 문자열이면 삭제
 * @property genre 새 장르. null이면 유지
 */
data class UpdateProjectRequest(
    @field:Size(min = 1, max = 80)
    val name: String? = null,

    @field:Size(max = 2000)
    val description: String? = null,

    val genre: Genre? = null
)

/**
 * `GET /api/projects`가 무엇을 세는지.
 *
 * 별도 `/api/admin/projects`를 내지 않고 파라미터로 가른 것은, 경로를 나누면 페이지네이션과 응답
 * DTO 가 두 벌이 되어 시간이 지나면 서로 어긋나기 때문이다. 두 값이 같은 응답 모양을 쓴다.
 */
enum class ProjectScope {
    /** 참여 중인 프로젝트. 값을 안 주면 이것이다. */
    MINE,

    /** 삭제되지 않은 전 프로젝트. `DEVELOPER` 등급만 쓸 수 있다. */
    ALL
}
