package kr.artel.orchestration.testrun.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testrun.dto.RunMapAnnotation
import kr.artel.orchestration.testrun.dto.RunMapFrame
import kr.artel.orchestration.testrun.dto.TestRunMapResponse
import kr.artel.orchestration.testrun.dto.TestRunMapSaveRequest
import kr.artel.orchestration.testrun.entity.TestRunMapEntity
import kr.artel.orchestration.testrun.repository.TestRunMapRepository
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs

/**
 * 테스트 런 맵의 배치 · 주석(ARTEL-1244).
 *
 * 맵은 통째로 저장한다. 한 번에 여러 사람이 고치면 **나중 저장을 막는다**: 요청의 `version` 이 지금 저장된 것과
 * 다르면 409 `run_map_stale` 을 돌려주고, 화면은 최신을 다시 읽는다. 병합은 하지 않는다.
 */
@Service
class TestRunMapService(
    private val runRepository: TestRunRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val mapRepository: TestRunMapRepository,
    private val appUserRepository: AppUserRepository,
    private val projectAccessService: ProjectAccessService,
    private val projectChanges: ProjectChangeStreamManager,
    private val objectMapper: ObjectMapper,
) {

    suspend fun get(projectId: Long, runId: Long, userId: Long): TestRunMapResponse {
        accessibleRun(projectId, runId, userId)
        return mapRepository.findById(runId)?.let { toResponse(it) } ?: empty(runId)
    }

    suspend fun save(projectId: Long, runId: Long, userId: Long, request: TestRunMapSaveRequest): TestRunMapResponse {
        accessibleRun(projectId, runId, userId)
        validate(request)
        val now = Instant.now()
        val stored = mapRepository.findById(runId)
        if ((stored?.version ?: 0) != request.version) stale()

        // 런에 든 TS 의 자리만 남긴다. 런에서 뺀 TS 의 자리는 버린다.
        val inRun = runScenarioRepository.findByTestRunIdOrderByPosition(runId).map { it.testScenarioId.toString() }.toSet()
        val layout = request.layout.filterKeys { it in inRun }
        // 만든 사람 · 때는 있던 주석이면 그대로, 새 주석이면 지금 저장한 사람이다.
        val previous = stored?.let { readAnnotations(it.annotations) }.orEmpty().associateBy { it.id }
        val annotations = request.annotations.map { annotation ->
            val before = previous[annotation.id]
            annotation.copy(
                createdBy = before?.createdBy ?: userId.toString(),
                createdAt = before?.createdAt ?: now,
            )
        }

        val layoutJson = Json.of(objectMapper.writeValueAsString(layout))
        val annotationsJson = Json.of(objectMapper.writeValueAsString(annotations))
        val changed = if (stored == null) mapRepository.insertFirst(runId, layoutJson, annotationsJson, userId, now)
        else mapRepository.updateIfVersion(runId, request.version, layoutJson, annotationsJson, userId, now)
        if (changed == 0) stale()

        projectChanges.notify(projectId, ProjectChangeTopic.TEST_RUNS)
        return toResponse(mapRepository.findById(runId)!!)
    }

    /** 경로의 프로젝트에 든 런이고, 그 프로젝트의 참여자일 때만. 아니면 없는 것처럼 404. */
    private suspend fun accessibleRun(projectId: Long, runId: Long, userId: Long) {
        val run = runRepository.findById(runId)
        if (run == null || run.projectId != projectId || !projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("테스트 런을 찾을 수 없습니다.")
        }
    }

    private fun stale(): Nothing = throw ConflictException(
        message = "다른 사람이 먼저 맵을 저장했습니다. 최신 맵을 다시 읽어 주세요.",
        code = "run_map_stale",
    )

    private fun validate(request: TestRunMapSaveRequest) {
        if (request.version < 0) bad("version 은 0 이상이어야 합니다.")
        if (request.annotations.size > MAX_ANNOTATIONS) bad("주석은 ${MAX_ANNOTATIONS}개까지입니다.")
        request.layout.values.forEach { coord(it.x); coord(it.y) }
        val ids = HashSet<String>()
        for (a in request.annotations) {
            if (a.id.isBlank() || a.id.length > MAX_ID_LENGTH) bad("주석 id 가 비었거나 너무 깁니다.")
            if (!ids.add(a.id)) bad("주석 id 가 겹칩니다: ${a.id}")
            when (a.type) {
                "note", "text" -> {
                    coord(a.x); coord(a.y); optionalSize(a.w); optionalSize(a.h)
                    val text = a.text ?: bad("${a.type} 에 text 가 없습니다.")
                    text(text)
                    if (a.type == "note" && a.color != null && a.color !in NOTE_COLORS) bad("메모 색은 $NOTE_COLORS 중 하나입니다.")
                }
                "rect", "ellipse", "section" -> {
                    coord(a.x); coord(a.y); size(a.w); size(a.h)
                    a.text?.let(::text)
                }
                "arrow" -> {
                    coord(a.x1); coord(a.y1); coord(a.x2); coord(a.y2)
                    // 휜 화살표의 조절점. 둘 다 있거나 둘 다 없다.
                    if ((a.cx == null) != (a.cy == null)) bad("arrow 의 cx · cy 는 함께 보냅니다.")
                    if (a.cx != null) { coord(a.cx); coord(a.cy) }
                }
                "pen" -> {
                    val points = a.points ?: bad("pen 에 points 가 없습니다.")
                    if (points.size < 2 || points.size > MAX_PEN_POINTS) bad("그림의 점은 2~${MAX_PEN_POINTS}개입니다.")
                    points.forEach { p -> if (p.size != 2) bad("그림의 점은 [x, y] 입니다."); coord(p[0]); coord(p[1]) }
                }
                else -> bad("모르는 주석 type 입니다: ${a.type}")
            }
        }
    }

    private fun coord(value: Double?) {
        if (value == null || !value.isFinite() || abs(value) > MAX_COORD) bad("좌표가 비었거나 범위를 벗어났습니다.")
    }

    private fun text(value: String) {
        if (value.length > MAX_TEXT_LENGTH) bad("글은 ${MAX_TEXT_LENGTH}자까지입니다.")
    }

    private fun optionalSize(value: Double?) {
        if (value != null) size(value)
    }

    private fun size(value: Double?) {
        if (value == null || !value.isFinite() || value < 0 || value > MAX_COORD) bad("크기가 비었거나 범위를 벗어났습니다.")
    }

    private fun bad(message: String): Nothing = throw BadRequestException(message, code = "invalid_run_map")

    private suspend fun toResponse(entity: TestRunMapEntity): TestRunMapResponse {
        val annotations = readAnnotations(entity.annotations)
        val userIds = (annotations.mapNotNull { it.createdBy?.toLongOrNull() } + listOfNotNull(entity.updatedBy)).toSet()
        val authors = if (userIds.isEmpty()) emptyMap()
        else appUserRepository.findAllById(userIds).toList().associate { user ->
            user.id.toString() to user.nickname.ifBlank { user.displayName }
        }
        return TestRunMapResponse(
            runId = entity.testRunId.toString(),
            version = entity.version,
            layout = objectMapper.readValue(entity.layout.asString(), LAYOUT_TYPE),
            annotations = annotations,
            updatedAt = entity.updatedAt,
            updatedBy = entity.updatedBy?.toString(),
            authors = authors,
        )
    }

    private fun readAnnotations(json: Json): List<RunMapAnnotation> = objectMapper.readValue(json.asString(), ANNOTATIONS_TYPE)

    private fun empty(runId: Long) = TestRunMapResponse(
        runId = runId.toString(), version = 0, layout = emptyMap(), annotations = emptyList(),
        updatedAt = null, updatedBy = null, authors = emptyMap(),
    )

    companion object {
        const val MAX_ANNOTATIONS = 1_000
        const val MAX_TEXT_LENGTH = 2_000
        const val MAX_PEN_POINTS = 2_000
        const val MAX_ID_LENGTH = 64
        const val MAX_COORD = 1_000_000.0
        val NOTE_COLORS = setOf("y", "p", "b", "g")
        private val LAYOUT_TYPE = object : TypeReference<Map<String, RunMapFrame>>() {}
        private val ANNOTATIONS_TYPE = object : TypeReference<List<RunMapAnnotation>>() {}
    }
}
