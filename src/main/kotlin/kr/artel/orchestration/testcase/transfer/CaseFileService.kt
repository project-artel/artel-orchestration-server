package kr.artel.orchestration.testcase.transfer

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.contract.CaseBatchItem
import kr.artel.orchestration.testcase.contract.CaseBatchRequest
import kr.artel.orchestration.testcase.contract.CaseBatchService
import kr.artel.orchestration.testcase.contract.CaseContractService
import kr.artel.orchestration.testcase.service.TestCaseVersionService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TC 를 Excel · CSV 로 내보내고 가져온다(ARTEL-1327).
 *
 * **가져오기는 만들기 · 고치기를 MCP 의 계약 배치([CaseBatchService])로 한다.** 번호가 있는 줄은 그 TC 를 고치고 고른 버전에
 * 넣는다(같은 번호는 버전만 오른다). 번호가 없으면 새로 만든다. 저장 전에 같은 계획을 미리 보여 준다([preview]) — 무엇이 새로
 * 생기고 무엇이 고쳐지며 어느 줄을 왜 못 넣는지.
 *
 * **바뀐 칸만 쓴다.** 도착 화면 · 종류는 지금 값과 다를 때만 보낸다. 빈칸은 "그대로" 다 — 파일로 지우지는 않는다(지우는
 * 것은 화면에서 한다). 계약(필요 · 남김)은 파일에 싣지 않는다([CaseFileColumn]). 도착 화면을 고치면 계약이 함께 쓰이므로, 그때는
 * 지금 계약을 그대로 실어 보낸다 — 빈 계약으로 보내면 지워진다.
 *
 * **결과 칸은 읽지 않는다.** 판정은 QA 가 정한다.
 */
@Service
class CaseFileService(
    private val projectAccess: ProjectAccessService,
    private val gameBuilds: GameBuildRepository,
    private val versions: TestCaseVersionService,
    private val caseContracts: CaseContractService,
    private val batch: CaseBatchService,
    private val databaseClient: DatabaseClient,
) {
    /** 내려줄 파일. */
    class Download(val fileName: String, val format: CaseFileFormat, val bytes: ByteArray)

    private sealed interface Target {
        data class Build(val id: Long, val name: String) : Target
        data object Unversioned : Target
        data object All : Target
    }

    /** 지금 저장된 TC 하나. [status] 는 TC 의 판정(버전을 고르지 않았을 때 결과 칸에 쓴다). */
    private data class CaseState(
        val id: Long,
        val scene: String,
        val precondition: String?,
        val step: String,
        val expectedValue: String,
        val kind: String,
        val status: String?,
        val arrivesAt: String?,
    )

    suspend fun export(projectId: Long, userId: Long, request: CaseFileExportRequest): Download {
        val project = projectAccess.requireMember(projectId, userId)
        val format = CaseFileFormat.of(request.format)
            ?: throw BadRequestException("format 은 xlsx 또는 csv 여야 합니다.", "invalid_format")
        val target = targetOf(projectId, userId, request.gameBuildId, allowAll = true)
        val states = statesOf(projectId)
        val numbers = numbersOf(projectId)
        // 버전을 골랐으면 그 버전의 TC 와 그 버전에서의 판정이다.
        val inTarget: List<Pair<Long, String?>> = when (target) {
            is Target.Build -> versions.cases(projectId, target.id, null, userId).items.map { it.id.toLong() to it.verificationStatus }
            Target.Unversioned -> versions.unversioned(projectId, userId).items.map { it.id.toLong() to it.verificationStatus }
            Target.All -> states.values.map { it.id to it.status }
        }
        val statusOf = inTarget.toMap()
        val ids = request.testCaseIds?.mapNotNull { it.toLongOrNull() }?.filter { it in statusOf }
            ?: inTarget.map { it.first }.sortedBy { numbers[it] ?: Long.MAX_VALUE }
        val rows = ids.mapNotNull { id ->
            val state = states[id] ?: return@mapNotNull null
            mapOf(
                CaseFileColumn.NUMBER to (numbers[id]?.let(::formatNumber) ?: ""),
                CaseFileColumn.KIND to kindLabel(state.kind),
                CaseFileColumn.SCENE to state.scene,
                CaseFileColumn.PRECONDITION to state.precondition.orEmpty(),
                CaseFileColumn.STEP to state.step,
                CaseFileColumn.EXPECTED to state.expectedValue,
                CaseFileColumn.ARRIVES_AT to state.arrivesAt.orEmpty(),
                CaseFileColumn.RESULT to statusLabel(statusOf[id] ?: state.status),
            )
        }
        val versionName = when (target) {
            is Target.Build -> target.name
            Target.Unversioned -> "버전없음"
            Target.All -> "전체"
        }
        val day = LocalDate.now(SEOUL).format(DateTimeFormatter.BASIC_ISO_DATE)
        return Download("${safe(project.name)}_${safe(versionName)}_TC_$day.${format.extension}", format, CaseFileCodec.write(format, rows))
    }

    /** 가져오기 양식(ARTEL-1328). 엑셀은 필터 · 종류 고르기 목록 · 작성법 시트가 든 빈 표다. */
    suspend fun template(projectId: Long, userId: Long, format: String?): Download {
        projectAccess.requireMember(projectId, userId)
        val chosen = CaseFileFormat.of(format ?: "xlsx") ?: throw BadRequestException("format 은 xlsx 또는 csv 여야 합니다.", "invalid_format")
        return Download("TC_가져오기_양식.${chosen.extension}", chosen, CaseFileCodec.template(chosen))
    }

    suspend fun preview(projectId: Long, userId: Long, gameBuildId: String?, fileName: String?, bytes: ByteArray): CaseImportPreview {
        projectAccess.requireMember(projectId, userId)
        val target = targetOf(projectId, userId, gameBuildId, allowAll = false)
        val format = CaseFileCodec.formatOf(bytes, fileName)
        val planned = plan(projectId, userId, target, CaseFileCodec.read(bytes, fileName))
        return CaseImportPreview(format.extension, planned.map { it.view }, countsOf(planned.map { it.view }))
    }

    suspend fun import(projectId: Long, userId: Long, gameBuildId: String?, fileName: String?, bytes: ByteArray): CaseImportResult {
        projectAccess.requireMember(projectId, userId)
        val target = targetOf(projectId, userId, gameBuildId, allowAll = false)
        val planned = plan(projectId, userId, target, CaseFileCodec.read(bytes, fileName))
        val buildId = (target as? Target.Build)?.id
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val rejected = planned.filter { it.view.status == CaseImportStatus.REJECTED }.map { it.view }.toMutableList()
        planned.filter { it.item != null }.chunked(CaseBatchService.MAX_CASES).forEach { chunk ->
            val response = batch.saveInto(projectId, userId, CaseBatchRequest(cases = chunk.map { it.item!! }), buildId)
            created += response.created
            updated += response.updated
            response.rejected.forEach { failure ->
                chunk.getOrNull(failure.index)?.let { rejected += it.view.copy(status = CaseImportStatus.REJECTED, reason = failure.reason) }
            }
        }
        val unchanged = planned.count { it.view.status == CaseImportStatus.SAME }
        return CaseImportResult(created, updated, unchanged, rejected.sortedBy { it.line })
    }

    /** 한 줄의 처리와, 저장할 것이면 보낼 항목. */
    private class Planned(val view: CaseImportRowView, val item: CaseBatchItem?)

    private suspend fun plan(projectId: Long, userId: Long, target: Target, rows: List<CaseFileRow>): List<Planned> {
        val states = statesOf(projectId)
        val numbers = numbersOf(projectId)
        val byNumber = numbers.entries.associate { (id, number) -> number to id }
        val contracts = caseContracts.list(projectId).cases.associateBy { it.testCaseId.toLong() }
        val inVersion = (target as? Target.Build)?.let { build ->
            versions.cases(projectId, build.id, null, userId).items.map { it.id.toLong() }.toSet()
        }
        val seen = mutableMapOf<Long, Int>()
        return rows.map { row ->
            val scene = row[CaseFileColumn.SCENE]
            val step = row[CaseFileColumn.STEP]
            val expected = row[CaseFileColumn.EXPECTED]
            val precondition = row[CaseFileColumn.PRECONDITION].ifBlank { null }
            val arrivesAt = row[CaseFileColumn.ARRIVES_AT].ifBlank { null }
            fun reject(reason: String, id: Long? = null) = Planned(
                CaseImportRowView(
                    line = row.line, status = CaseImportStatus.REJECTED, testCaseId = id?.toString(),
                    caseNumber = id?.let { numbers[it] }?.let(::formatNumber), scene = scene, step = step, expectedValue = expected,
                    reason = reason,
                ),
                null,
            )
            val empty = listOf(CaseFileColumn.SCENE to scene, CaseFileColumn.STEP to step, CaseFileColumn.EXPECTED to expected)
                .filter { it.second.isBlank() }.map { it.first.label }
            if (empty.isNotEmpty()) return@map reject("${empty.joinToString(" · ")} 칸이 비어 있습니다.")
            val kind = when (val text = row[CaseFileColumn.KIND]) {
                "" -> null
                else -> kindOf(text) ?: return@map reject("종류는 기능 · 예외 중 하나여야 합니다: $text")
            }
            val id = when (val resolved = targetIdOf(row, byNumber, states)) {
                is Resolved.Missing -> return@map reject(resolved.reason)
                is Resolved.Found -> resolved.id
                null -> null
            }
            if (id != null) {
                seen[id]?.let { first -> return@map reject("같은 TC(${numbers[id]?.let(::formatNumber) ?: id})가 파일의 ${first}번째 줄에도 있습니다.", id) }
                seen[id] = row.line
            }
            if (id == null) {
                return@map Planned(
                    CaseImportRowView(row.line, CaseImportStatus.NEW, kind = kind ?: "FUNCTION", scene = scene, step = step, expectedValue = expected),
                    CaseBatchItem(
                        scene = scene, precondition = precondition, step = step, expectedValue = expected,
                        arrivesAt = arrivesAt, kind = kind,
                    ),
                )
            }
            val now = states.getValue(id)
            val contract = contracts[id]
            val changes = buildList {
                if (scene != now.scene) add(CaseFileColumn.SCENE.label)
                if (precondition.orEmpty() != now.precondition.orEmpty()) add(CaseFileColumn.PRECONDITION.label)
                if (step != now.step) add(CaseFileColumn.STEP.label)
                if (expected != now.expectedValue) add(CaseFileColumn.EXPECTED.label)
                if (kind != null && kind != now.kind) add(CaseFileColumn.KIND.label)
                if (arrivesAt != null && arrivesAt != now.arrivesAt) add(CaseFileColumn.ARRIVES_AT.label)
                if (inVersion != null && id !in inVersion) add(IN_VERSION)
            }
            val view = CaseImportRowView(
                line = row.line,
                status = if (changes.isEmpty()) CaseImportStatus.SAME else CaseImportStatus.EDIT,
                testCaseId = id.toString(),
                caseNumber = numbers[id]?.let(::formatNumber),
                kind = kind ?: now.kind,
                scene = scene, step = step, expectedValue = expected,
                changes = changes,
            )
            if (changes.isEmpty()) return@map Planned(view, null)
            // 도착 화면을 쓰면 계약(필요 · 남김)이 함께 덮인다. 지금 계약을 실어 보낸다 — 빈칸으로 보내면 지워진다.
            val arrivalChanged = CaseFileColumn.ARRIVES_AT.label in changes
            val item = CaseBatchItem(
                testCaseId = id.toString(), scene = scene, precondition = precondition, step = step, expectedValue = expected,
                needs = if (arrivalChanged) contract?.needs.orEmpty() else null,
                leaves = if (arrivalChanged) contract?.leaves.orEmpty() else null,
                arrivesAt = if (arrivalChanged) arrivesAt else null,
                kind = kind?.takeIf { it != now.kind },
            )
            Planned(view, item)
        }
    }

    private sealed interface Resolved {
        data class Found(val id: Long) : Resolved
        data class Missing(val reason: String) : Resolved
    }

    /** 번호(`TC-012` · `12`) 칸이 먼저고, 없으면 id 칸이다. id 칸의 `TC-…` 도 번호로 읽는다. 둘 다 비면 새 TC(null). */
    private fun targetIdOf(row: CaseFileRow, byNumber: Map<Long, Long>, states: Map<Long, CaseState>): Resolved? {
        val number = row[CaseFileColumn.NUMBER]
        val raw = row[CaseFileColumn.ID]
        fun byCaseNumber(text: String): Resolved {
            val value = text.trim().removePrefix("TC-").removePrefix("tc-").trimStart('0').toLongOrNull()
            val id = value?.let { byNumber[it] }?.takeIf { it in states }
            return id?.let { Resolved.Found(it) } ?: Resolved.Missing("이 프로젝트에 ${text.trim()} 이(가) 없습니다. 번호를 비우면 새로 만들어요.")
        }
        return when {
            number.isNotBlank() -> byCaseNumber(number)
            raw.isBlank() -> null
            raw.trim().startsWith("TC-", ignoreCase = true) -> byCaseNumber(raw)
            else -> raw.trim().toLongOrNull()?.takeIf { it in states }?.let { Resolved.Found(it) }
                ?: Resolved.Missing("이 프로젝트에 id ${raw.trim()} 인 TC 가 없습니다. id 를 비우면 새로 만들어요.")
        }
    }

    private suspend fun targetOf(projectId: Long, userId: Long, gameBuildId: String?, allowAll: Boolean): Target {
        val value = gameBuildId?.trim().orEmpty()
        return when {
            value.isEmpty() && allowAll -> Target.All
            value.isEmpty() -> throw BadRequestException("가져올 버전(gameBuildId)을 정해 주세요. 버전 없음은 unversioned 입니다.", "missing_version")
            value.equals(UNVERSIONED, ignoreCase = true) -> Target.Unversioned
            else -> {
                val id = value.toLongOrNull() ?: throw NotFoundException("버전을 찾을 수 없습니다.")
                val build = gameBuilds.findAccessibleById(id, projectId, userId) ?: throw NotFoundException("버전을 찾을 수 없습니다.")
                Target.Build(id, build.label?.takeIf { it.isNotBlank() } ?: build.version)
            }
        }
    }

    private suspend fun statesOf(projectId: Long): Map<Long, CaseState> =
        databaseClient.sql(
            """
            SELECT id, scene, precondition, step, expected_value, kind, verification_status,
                   NULLIF(metadata->>'arrives_at', '') AS arrives_at
              FROM test_case WHERE project_id = :p
            """.trimIndent()
        ).bind("p", projectId).map { row, _ ->
            CaseState(
                id = row.get("id", java.lang.Long::class.java)!!.toLong(),
                scene = row.get("scene", String::class.java).orEmpty(),
                precondition = row.get("precondition", String::class.java),
                step = row.get("step", String::class.java).orEmpty(),
                expectedValue = row.get("expected_value", String::class.java).orEmpty(),
                kind = row.get("kind", String::class.java) ?: "FUNCTION",
                status = row.get("verification_status", String::class.java),
                arrivesAt = row.get("arrives_at", String::class.java),
            )
        }.flow().toList().associateBy { it.id }

    /** TC id → 프로젝트 안 번호(화면의 `TC-012`). */
    private suspend fun numbersOf(projectId: Long): Map<Long, Long> =
        databaseClient.sql("SELECT test_case_id, case_number FROM test_case_identifier WHERE project_id = :p")
            .bind("p", projectId)
            .map { row, _ -> row.get("test_case_id", java.lang.Long::class.java)!!.toLong() to row.get("case_number", java.lang.Long::class.java)!!.toLong() }
            .flow().toList().toMap()

    private fun countsOf(rows: List<CaseImportRowView>) = CaseImportCounts(
        new = rows.count { it.status == CaseImportStatus.NEW },
        edit = rows.count { it.status == CaseImportStatus.EDIT },
        same = rows.count { it.status == CaseImportStatus.SAME },
        rejected = rows.count { it.status == CaseImportStatus.REJECTED },
    )

    companion object {
        const val UNVERSIONED = "unversioned"

        /** 고칠 줄의 바뀌는 칸 목록에서, 그 버전에 아직 없던 TC 를 넣는 것. */
        const val IN_VERSION = "이 버전에 넣음"

        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun formatNumber(number: Long): String = "TC-" + number.toString().padStart(3, '0')

        fun kindLabel(kind: String): String = if (kind == "EXCEPTION") "예외" else "기능"

        /** 종류 칸의 글. 한국어 · 영어 · enum 이름을 받는다. 모르면 null. */
        fun kindOf(text: String): String? = when (text.trim().lowercase()) {
            "기능", "function", "functional" -> "FUNCTION"
            "예외", "exception" -> "EXCEPTION"
            else -> null
        }

        fun statusLabel(status: String?): String = when (status?.uppercase()) {
            "VERIFIED" -> "통과"
            "FAILED" -> "실패"
            "REGRESSION" -> "재검증 필요"
            "BROKEN" -> "깨짐"
            else -> "미검증"
        }

        /** 파일 이름에 못 쓰는 글자를 뺀다. */
        private fun safe(text: String): String = text.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifEmpty { "project" }
    }
}
