package kr.artel.orchestration.game.dto

import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * 게임 빌드 수정 요청. 부분 수정이라 null인 필드는 손대지 않는다.
 *
 * 지울 때는 null이 아니라 빈 문자열을 보낸다. Jackson은 "키가 없음"과 "값이 null"을 같은
 * null로 만들기 때문에, null에 "지우기" 의미를 주면 나머지 필드만 수정하려는 요청이
 * 나머지까지 지워버린다. 빈 문자열은 저장 시 null로 정규화한다.
 *
 * version은 여기에 없다. SDK가 관찰해 보고한 값이라 사람이 고칠 대상이 아니다.
 *
 * @property label 새 표시 이름. null이면 유지, 빈 문자열이면 삭제
 * @property notes 새 메모. null이면 유지, 빈 문자열이면 삭제
 */
data class UpdateGameBuildRequest(
    @field:Size(max = 80)
    val label: String? = null,

    @field:Size(max = 2000)
    val notes: String? = null
)

/**
 * 게임 빌드 응답.
 *
 * @property version Unity Player Settings에서 관찰한 값. 읽기 전용이다. editor 행이면 editor 가 마지막으로
 * 보고한 값이라 다음 editor 등록에서 바뀔 수 있다
 * @property isEditor 프로젝트의 editor 행인가(ARTEL-1014). 게임 버전이 아니라 editor 작업 상태다. 목록에서
 * 마지막에 온다
 */
data class GameBuildResponse(
    val id: String,
    val projectId: String,
    val version: String,
    val label: String?,
    val notes: String?,
    val isEditor: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** 목록 응답 봉투. */
data class GameBuildListResponse(
    val items: List<GameBuildResponse>
)
