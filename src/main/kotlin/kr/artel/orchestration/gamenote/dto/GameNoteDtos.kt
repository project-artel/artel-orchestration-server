package kr.artel.orchestration.gamenote.dto

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import kr.artel.orchestration.gamenote.entity.GenreModule
import java.time.Instant
import kotlin.reflect.KClass

/** 노트 본문의 최대 글자 수. `project_game_note.body` 의 CHECK 와 PoC 의 `MAX_NOTE_CHARS` 와 같다. */
const val MAX_GAME_NOTE_CHARS = 20000

/**
 * `GET /api/projects/{projectId}/game-notes` 와 `PUT` 의 응답. 프로젝트의 최신 버전이다.
 *
 * 저장한 적이 없으면 [version] 0, 빈 [body], 빈 [genres] 이고 [updatedAt] · [updatedBy] 는 null 이다.
 *
 * @property version 다음 `PUT` 의 `baseVersion` 으로 그대로 돌려줄 값
 */
data class GameNotesResponse(
    val version: Int,
    val body: String,
    val genres: List<GenreModule>,
    val updatedAt: Instant?,
    val updatedBy: GameNoteAuthorResponse?
)

/** 그 버전을 저장한 사람. 모양은 기획서의 `uploadedBy` 와 같다. */
data class GameNoteAuthorResponse(
    val id: String,
    val displayName: String
)

/**
 * `PUT /api/projects/{projectId}/game-notes` 요청 본문.
 *
 * 세 필드 모두 보내야 한다. 비우려면 `body: ""`, `genres: []` 를 보낸다.
 *
 * @property body [MAX_GAME_NOTE_CHARS] 자 이하. 길이는 JavaScript `String.length` 와 같은 UTF-16 code unit 으로 센다
 * @property genres [GenreModule.wire] 값. 모르는 이름이나 같은 이름 두 번은 400 이다. 순서는 따지지 않는다
 * @property baseVersion 편집을 시작할 때 받은 `version`. 지금 최신이 아니면 409 `game_notes_conflict` 다
 */
data class UpdateGameNotesRequest(
    @field:NotNull
    @field:Size(max = MAX_GAME_NOTE_CHARS)
    val body: String? = null,

    @field:NotNull
    @field:GenreModuleNames
    val genres: List<String>? = null,

    @field:NotNull
    @field:PositiveOrZero
    val baseVersion: Int? = null
)

/**
 * [GenreModule] 이름 목록인지, 같은 이름이 두 번 나오지 않는지 본다.
 *
 * Jackson 이 `List<GenreModule>` 로 바로 읽게 두면 모르는 이름은 decode 오류가 되어 `fields` 없는 400 이 된다.
 * 문자열로 받아 여기서 걸러야 `fields.genres` 에 이유가 실린다.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [GenreModuleNamesValidator::class])
annotation class GenreModuleNames(
    val message: String = "알 수 없는 장르입니다.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class GenreModuleNamesValidator : ConstraintValidator<GenreModuleNames, List<String>?> {
    override fun isValid(value: List<String>?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val reason = when {
            value.any { GenreModule.fromWire(it) == null } ->
                "${GenreModule.WIRE_NAMES.joinToString(", ")} 중에서 고르세요."
            value.toSet().size != value.size -> "같은 장르를 두 번 보냈습니다."
            else -> return true
        }
        // template 에 요청 값을 넣지 않는다. Hibernate Validator 는 template 의 `${...}` 를 EL 로 평가한다.
        context.disableDefaultConstraintViolation()
        context.buildConstraintViolationWithTemplate(reason).addConstraintViolation()
        return false
    }
}
