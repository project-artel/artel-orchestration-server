package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.AllTestCasesResponse
import kr.artel.orchestration.testcase.dto.AuthoringTestCase
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.testcase.dto.CaseGuard
import kr.artel.orchestration.testcase.dto.ValueMove
import kr.artel.orchestration.testscenario.service.AuthoringCases
import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testcase.dto.SceneEdge
import kr.artel.orchestration.testcase.dto.SceneExit
import kr.artel.orchestration.testcase.dto.TestCaseBulkDeleteRequest
import kr.artel.orchestration.testcase.dto.TestCaseBulkDeleteResponse
import kr.artel.orchestration.testcase.dto.TestCaseMoveRequest
import kr.artel.orchestration.testcase.dto.TestCaseMoveResponse
import kr.artel.orchestration.testcase.dto.parseOptionalWireId
import kr.artel.orchestration.testcase.dto.parseWireIds
import kr.artel.orchestration.testcase.dto.TestCaseCoverageResponse
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseDetailResponse
import kr.artel.orchestration.testcase.dto.TestCaseListResponse
import kr.artel.orchestration.testcase.dto.TestCaseResponse
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.dto.toTestCaseDetailResponse
import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.service.ScenarioStateReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import kr.artel.orchestration.testscenario.service.CaseConditionReader

/**
 * TestCase 도메인 서비스(코루틴). 재사용 케이스 라이브러리의 CRUD를 담당한다.
 *
 * 접근은 프로젝트 참여(project_member)로 인가하며, 비참여자에겐 **null**(존재하지 않는 것처럼)로 응답한다
 * — 컨트롤러가 404로 매핑한다. 값은 자연어 그대로 저장한다. 잘못된 입력은 400으로 막는다.
 *
 * 참고(코루틴 전환): Reactor의 `Mono.empty()` = "숨김" 은 코루틴에서 **nullable 반환(null)** 로 표현된다.
 */
@Service
class TestCaseService(
    // 케이스의 전제를 읽는 유일한 창구(ARTEL-627). 문장이 아니라 구조에서 읽는다.
    private val conditions: CaseConditionReader,

    private val repository: TestCaseRepository,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
    private val folderService: TestCaseFolderService,
    private val transactionalOperator: TransactionalOperator,
    private val gameBuilds: GameBuildRepository,
    private val manualCases: ManualCaseVersionService,
    private val folderItems: TestCaseFolderItemRepository,
    // 저작이 TC 와 지도 사실을 읽는 자리(ARTEL-1064). 런이 빌드 버전을 따르면 그 버전의 것을 준다.
    private val authoringCases: AuthoringCases,
    private val reader: TestCaseReader,
) {

    private val logger = LoggerFactory.getLogger(TestCaseService::class.java)
    /**
     * 화면이 읽는 케이스 목록(최근 것부터). 비참여자면 빈 목록.
     *
     * 씬/검증상태 필터가 있었지만 지웠다 — 보내는 쪽이 없었다. 화면은 전량을 받아 브라우저에서
     * 거르고, 필터를 타는 경로가 없으니 그 코드는 "동작한다"고 말할 근거도 없었다.
     * 서버에서 걸러야 할 만큼 목록이 커지면 그때 실제 소비자에 맞춰 다시 넣는 편이 낫다.
     */
    suspend fun listTestCases(projectId: Long, userId: Long, gameBuildId: Long? = null): TestCaseListResponse {
        if (!projectAccessService.isMember(projectId, userId)) return TestCaseListResponse(emptyList())
        val folderIds = folderItems.findFirstFolders(projectId).toList().associate { it.testCaseId to it.folderId }
        if (gameBuildId != null) {
            // 버전을 따르는 화면(TR)은 그 버전에 든 케이스만, 그 버전 내용으로 받는다(ARTEL-1069).
            gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("버전을 찾을 수 없습니다.")
            val items = reader.inScope(projectId, CaseScope.Version(gameBuildId)).asReversed()
                .map { it.toTestCaseResponse(folderIds[it.id]) }
            return TestCaseListResponse(items)
        }
        val items = repository.findByProjectIdOrderByIdDesc(projectId)
            .map { it.toTestCaseResponse(folderIds[it.id]) }
            .toList()
        return TestCaseListResponse(items)
    }

    /**
     * 저작 Agent에 실을 프로젝트 TestCase 전량 목록(ARTEL-318).
     *
     * [listTestCases]와 달리 좁게 낸다. **거르지 않는 것이 이 조회의 목적**이기 때문이다 — 지금 Agent는
     * 벡터 검색으로 30~40건만 보고, 나머지는 존재조차 모른 채 시나리오를 만든다. 그 실패를 없애려면
     * 전량이어야 한다. 전량을 실어도 되는지는 측정으로 답이 났다(1000건 기준 74.4k, 캐시 대상).
     *
     * 정렬을 `id ASC`로 고정하는 이유는 [TestCaseRepository.findTestCaseListByProjectIdOrderByIdAsc]에 적었다.
     *
     * 비참여자에겐 빈 목록 — [listTestCases]와 같은 판단이다(존재 자체를 숨긴다).
     */
    suspend fun getAllTestCases(projectId: Long, userId: Long): AllTestCasesResponse {
        if (!projectAccessService.isMember(projectId, userId)) return AllTestCasesResponse(emptyList())
        return AllTestCasesResponse(repository.findTestCaseListByProjectIdOrderByIdAsc(projectId).toList())
    }

    /**
     * 저작 세션에 실을 전량. [getAllTestCases]에 **정규화된 상태**를 얹은 것이다(ARTEL-466).
     *
     * 사전조건 문장을 Agent와 오케가 각자 해석하던 것을 한쪽으로 모은다 — 두 해석이 어긋나면
     * Agent가 짠 순서를 코드가 다른 상태로 계산해 메우게 되고, 그 어긋남은 화면에 아무 표시도
     * 남기지 않는다. 읽는 규칙은 경로 계산이 쓰는 것과 같은 [ScenarioStateReader]다.
     *
     * 비참여자에겐 빈 목록 — 다른 조회와 같은 판단이다(존재 자체를 숨긴다).
     */
    /**
     * [onlyIds] 가 있으면 그 케이스만 보낸다(ARTEL-1048) — TR 이 고른 TC 버전에 든 것. 없으면 프로젝트 전체다.
     */
    suspend fun getAuthoringCases(projectId: Long, userId: Long, onlyIds: Set<Long>? = null): List<AuthoringTestCase> {
        if (!projectAccessService.isMember(projectId, userId)) return emptyList()
        val changed = valuesChangedByCases(projectId)
        // **길은 우리가 찾지 않지만, 지도가 아는 것은 보낸다**(ARTEL-628).
        val exits = sceneExits(projectId)
        // **어느 화면에서 움직이는 값인지 미리 말한다**(ARTEL-635). 거절하고 고치게 하는 것은
        // 뒷수습이다 — 저작이 짤 때 알아야 스테이지를 안 깬 채로 지도를 활보하지 않는다.
        val raisers = valueRaisers(projectId)
        // **화면 이름만으로는 "들렀다 오면 되나"로 읽힌다**(ARTEL-646). 얼마씩·시킬 수 있나·어떤
        // 조건에서까지 함께 보낸다 — 그 넷이 지도에 나란히 적혀 있고, 실측(A/B)에서 같은 모델이
        // 여정을 26조각에서 10개로 줄였다.
        val moves = valueMoves(projectId)
        // **조작의 기계값도 함께 보낸다**(ARTEL-772). 앞서 이것만 `explain_case` 도구로 물어야
        // 했는데, 그 도구가 준 나머지(씬 · 요구 · 남기는 것)는 이 목록에 이미 실려 있었다.
        // 한 칸을 옮기면 왕복이 사라진다 — 늘 참인 사실은 목록이 한 번 말한다.
        val inputs = caseInputs(projectId)
        // 런이 빌드 버전을 따르면 케이스 내용은 그 버전 몫이다(ARTEL-1064). 맥락이 없으면 대표 행 그대로다.
        return authoringCases.all(projectId)
            // **깨진 것은 저작 재료가 아니다**(ARTEL-685). 지도를 다시 적재하면 더 이상 뒷받침되지
            // 않는 케이스가 `BROKEN` 으로 남는다. 보내면 모델이 그것을 담고, 커버리지 기준에는
            // 없으므로 "없는 케이스 번호"로 걸려 저작이 통째로 막힌다 — 실측(런 268)에서 14건.
            // 보내는 것과 세는 것이 같은 집합이어야 한다.
            .filter { it.verificationStatus != "BROKEN" }
            .filter { onlyIds == null || it.id in onlyIds }
            .map { case ->
            AuthoringTestCase(
                id = case.id!!,
                scene = ScenarioStateReader.sceneOf(case) ?: case.scene,
                step = case.step,
                precondition = case.precondition,
                expectedValue = case.expectedValue,
                verificationStatus = case.verificationStatus,
                // **문장이 아니라 구조에서 읽는다**(ARTEL-627).
                stateBefore = conditions.guardsOf(case)
                    .map { guard ->
                        CaseGuard(
                            guard.variable, guard.operator, guard.value,
                            raisedIn = raisers[ScenarioStateReader.normalize(guard.path)].orEmpty(),
                            moves = movesFor(moves, guard),
                        )
                    },
                // 케이스 메타가 먼저다 — 구버전 엑셀 경로로 들어온 행은 거기 답이 있다. 지도가 낸
                // 행은 그 칸이 없어서 비어 오고, 그때 지도가 답한다(ARTEL-606).
                stateAfter = ScenarioStateReader.stateAfter(case, objectMapper)
                    .ifEmpty { changed[case.id].orEmpty() } + arrival(case),
                exits = exits[ScenarioStateReader.sceneOf(case) ?: case.scene].orEmpty(),
                input = inputs[case.id].orEmpty(),
            )
        }.toList()
    }

    /**
     * 케이스마다 **실행하면 무엇이 바뀌나**(ARTEL-606).
     *
     * 저작이 실행할 수 없는 스텝을 지어내던 자리다 — 케이스가 `position == 1 인 상태에서` 라고만
     * 하고 position 이 무엇을 하면 1이 되는지는 말하지 않아, 모델이 `case_id` 없는 "…상태로
     * 준비한다"를 적었다. QA 실행이 따라갈 것이 없는 문장이다.
     *
     * 답은 지도의 `capability_effect` 에 있고 `capability_key` 가 그 자리로 가는 길이다. 계약은
     * 안 바꾼다 — `state_after` 는 이미 `AuthoringTestCase` 에 있고 에이전트가 읽는 자리다.
     *
     * **이름은 마지막 마디로 통일한다.** `CaseGuard` 가 같은 규칙이라, 그러지 않으면 한쪽의
     * `MapMove.StagePosition` 과 다른 쪽의 `StagePosition` 이 서로 다른 값으로 보인다.
     *
     * 못 읽어도 저작을 세우지 않는다. 이것이 없으면 예전처럼 빈 map 이고, 모델은 지금처럼 지어낸다.
     */
    /**
     * 실행하면 **어느 화면이 되나**(ARTEL-614).
     *
     * 씬 전환도 상태 변화다. 저작이 `state_after` 와 다음 케이스의 `scene` 을 맞추면 그것이 곧
     * 씬 브리지이고, 기대결과 산문을 읽을 필요가 없어진다.
     *
     * `state_after` 에 실어 **계약을 안 바꾼다**(ARTEL-606 과 같은 판단). 키를 `scene` 으로 두는
     * 것은 다음 케이스의 `scene` 칸과 같은 말이라 맞추는 쪽이 헷갈리지 않기 때문이다 — 실측에서
     * 그 이름의 게임 값을 요구하는 전제는 없다.
     */
    private fun arrival(case: TestCaseEntity): Map<String, String> =
        runCatching { objectMapper.readTree(case.metadata.asString()) }.getOrNull()
            ?.path(MapTestCaseWriter.ARRIVES_AT)?.asText(null)
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf("scene" to it) }
            .orEmpty()

    /**
     * 화면마다 **한 걸음에 갈 수 있는 곳들**(ARTEL-628).
     *
     * 닿을 수 있는 화면 전부로 답하지 않는다. 재 보면 어느 화면에서든 일곱 화면 전부에 닿아
     * (강하게 이어진 그래프) 전부 같은 답이 되고, 그러면 아무것도 못 가른다.
     *
     * 못 읽으면 빈 map 이다. 길 안내가 없어도 저작은 돌아야 한다 — 그건 도움말이지 재료가 아니다.
     */
    /**
     * 값마다 **움직이는 화면들**(ARTEL-635).
     *
     * `+1` 같은 증감만 본다. 확정값(`0`)은 되돌리는 것이지 진행이 아니라, 그것까지 세면
     * 타이틀 화면이 모든 값의 출처가 된다.
     */
    /**
     * 그 가드가 말하는 값을 **바꾸는 자리들**(ARTEL-646).
     *
     * **전체 이름으로 먼저 맞춘다.** 마지막 마디로만 맞추면 `StageDataSingleton.stagePosition` 이
     * `MapMove.StagePosition` 자리에 딸려 온다 — 실측에서 일곱 줄 중 여섯이 남의 것이었고, 답
     * 한 줄이 그 속에 묻혔다. 꼬리가 서로를 포함할 때만 같은 값으로 본다(`Player.hp` 와 `hp`).
     *
     * 전체 이름으로 하나도 못 찾으면 마지막 마디로 물러선다 — 못 찾는 것보다는 낫고, 그때는
     * 이름이 흐린 것이라 저작도 흐린 채로 받는 편이 정직하다.
     */
    private fun movesFor(all: Map<String, List<OwnedMove>>, guard: Guard): List<ValueMove> {
        val leaf = ScenarioStateReader.normalize(guard.path)
        val candidates = all[leaf].orEmpty()
        if (candidates.isEmpty()) return emptyList()
        val exact = candidates.filter { (owner, _) ->
            owner == guard.path || owner.endsWith(".${guard.path}") || guard.path.endsWith(".$owner")
        }
        return (if (exact.isNotEmpty()) exact else candidates).map { it.move }.distinct()
    }

    private suspend fun valueMoves(projectId: Long): Map<String, List<OwnedMove>> = runCatching {
        authoringCases.valueMoves(projectId).toList()
            .map { row ->
                OwnedMove(
                    owner = row.target,
                    move = ValueMove(
                        scene = row.scene,
                        by = row.detail,
                        // **시킬 수 없으면 비운다.** 그것이 "사람이 조건을 만들어야 한다"는 뜻이고,
                        // 화면 이름만 있던 자리에서 가장 크게 빠져 있던 사실이다.
                        how = row.operation?.takeIf { row.actionability != Actionability.NOT_A_STEP.wire },
                        whenTrue = conditionText(row.conditionTree),
                    ),
                )
            }
            .groupBy { ScenarioStateReader.normalize(it.owner) }
    }.onFailure { logger.warn("값이 어떻게 움직이는지 조회 실패 — 화면 이름만 보낸다: ${it.message}") }
        .getOrElse { emptyMap() }

    /** 조건을 사람이 읽는 한 줄로. 구조에서 읽으므로 문장을 되파싱하지 않는다. */
    private fun conditionText(tree: io.r2dbc.postgresql.codec.Json?): String? = runCatching {
        val node = EvidenceParser(objectMapper).parseCondition(objectMapper.readTree(tree?.asString() ?: return null))
        ScenarioStateReader.guardsIn(node)
            .joinToString(" 그리고 ") { "${it.variable} ${it.operator} ${it.value}" }
            .ifBlank { null }
    }.getOrNull()

    /** 값 이름을 함께 든 이동. 가드와 맞출 때 전체 이름이 필요하다. */
    private data class OwnedMove(val owner: String, val move: ValueMove)

    private suspend fun valueRaisers(projectId: Long): Map<String, List<String>> = runCatching {
        authoringCases.valueRaisers(projectId).toList()
            .groupBy({ ScenarioStateReader.normalize(it.target) }, { it.scene })
            .mapValues { (_, scenes) -> scenes.distinct().sorted() }
    }.getOrElse { emptyMap() }

    /**
     * 케이스마다 조작의 기계값. 지도를 못 되짚는 행(구버전 엑셀 경로)은 담기지 않는다 —
     * `capability_key` 가 없어서 가리킬 조작이 없다.
     */
    private suspend fun caseInputs(projectId: Long): Map<Long, String> = runCatching {
        authoringCases.caseInputs(projectId).toList()
            .filter { it.input.isNotBlank() }
            .associate { it.testCaseId to it.input }
    }.getOrElse { emptyMap() }

    private suspend fun sceneExits(projectId: Long): Map<String, List<SceneExit>> =
        sceneEdges(projectId)
            .groupBy { it.fromScene }
            .mapValues { (_, edges) -> edges.map { SceneExit(it.toScene, it.by) } }

    /**
     * 지도 전체의 화면 간선(흐름 없는 저작 실험).
     *
     * [sceneExits] 는 케이스가 서 있는 화면 것만 케이스에 딸려 나간다. 케이스 없는 화면의
     * 간선은 그 길로는 모델에게 안 보이고, 빈 자리를 모델은 "못 간다"로 읽는다. 세션을 열 때
     * 이 목록을 한 번 더 실어 그 구멍을 막는다.
     *
     * 정렬은 프롬프트 캐시 때문이다 — 같은 지도면 바이트까지 같아야 앞부분이 재활용된다.
     */
    suspend fun sceneEdges(projectId: Long): List<SceneEdge> = runCatching {
        authoringCases.sceneExits(projectId).toList()
            .groupBy { it.fromScene to it.toScene }
            // 한 화면으로 가는 길이 여럿이면 **누를 것이 있는 쪽**을 먼저 든다.
            .map { (pair, ways) -> SceneEdge(pair.first, pair.second, ways.firstNotNullOfOrNull { it.byOperation }) }
            .sortedWith(compareBy({ it.fromScene }, { it.toScene }))
    }.onFailure { logger.warn("화면 간선 조회 실패 — 없이 보낸다: ${it.message}") }
        .getOrElse { emptyList() }

    /**
     * 게임을 켜면 값이 무엇으로 시작하나(흐름 없는 저작 실험).
     *
     * [kr.artel.orchestration.testscenario.service.ScenarioFlowPlanner] 가 흐름을 세울 때 쓰던
     * 것과 같은 규칙([ScenarioStateReader.defaultOf] · [ScenarioStateReader.normalize])이다.
     * 흐름을 끄면 이 값이 아무 데도 안 나가므로, 세션을 열 때 직접 싣는다.
     */
    suspend fun startingValues(projectId: Long): Map<String, String> = runCatching {
        authoringCases.startingValues(projectId).toList()
            .mapNotNull { row -> ScenarioStateReader.defaultOf(row.detail)?.let { ScenarioStateReader.normalize(row.name) to it } }
            .sortedBy { it.first }
            .toMap()
    }.onFailure { logger.warn("처음 값 조회 실패 — 없이 보낸다: ${it.message}") }
        .getOrElse { emptyMap() }

    private suspend fun valuesChangedByCases(projectId: Long): Map<Long, Map<String, String>> = runCatching {
        authoringCases.valuesChangedByCases(projectId).toList()
            .groupBy { it.caseId }
            .mapValues { (_, writes) ->
                writes.mapNotNull { write ->
                    write.detail?.takeIf { it.isNotBlank() }
                        ?.let { ScenarioStateReader.normalize(write.target) to it }
                }.toMap()
            }
    }.onFailure { logger.warn("케이스가 바꾸는 값 조회 실패 — 저작에 state_after 를 못 싣는다: ${it.message}") }
        .getOrElse { emptyMap() }

    /**
     * 프로젝트의 커버리지(ARTEL-403). 비참여자면 전부 0 — 목록과 같은 판단이다(존재를 숨긴다).
     *
     * **두 축을 함께 낸다.** 저작 커버리지(어떤 시나리오가 참조하는가)와 검증 커버리지(QA 런이
     * 무엇을 냈는가)는 다른 질문이고, 사용자가 할 일도 다르다 — 저작만 되고 안 돌린 케이스는
     * 실행할 것이고, 깨진 케이스는 고칠 것이다.
     *
     * `unauthored`를 따로 내는 것은 화면이 빼기를 하지 않게 하기 위해서다. 같은 수를 두 곳에서
     * 계산하면 언젠가 두 값이 갈리고, 그때 어느 쪽이 맞는지 알 수 없다.
     */
    suspend fun coverage(projectId: Long, userId: Long): TestCaseCoverageResponse {
        if (!projectAccessService.isMember(projectId, userId)) {
            return TestCaseCoverageResponse(0, 0, 0, 0, 0, 0, emptyList())
        }
        val total = repository.countByProjectId(projectId).toInt()
        val uncoveredScenes = repository.findScenesOfUncovered(projectId).toList()
        val unauthored = uncoveredScenes.sumOf { it.count }.toInt()
        return TestCaseCoverageResponse(
            total = total,
            authored = total - unauthored,
            unauthored = unauthored,
            verified = repository.countByProjectIdAndVerificationStatus(
                projectId, VerificationStatus.VERIFIED.name
            ).toInt(),
            draft = repository.countByProjectIdAndVerificationStatus(
                projectId, VerificationStatus.DRAFT.name
            ).toInt(),
            broken = repository.countByProjectIdAndVerificationStatus(
                projectId, VerificationStatus.BROKEN.name
            ).toInt(),
            uncoveredScenes = uncoveredScenes,
        )
    }

    /**
     * 케이스 생성. scene/step/expectedValue 필수. 상태는 DRAFT로 시작. 비참여자면 null(→404).
     *
     * `folderId` 가 있으면 이 프로젝트 folder 여야 한다(아니면 404). 그 folder 의 끝에 넣는다(ARTEL-1023).
     * folder 확인과 저장 사이에 그 folder 가 지워지지 않게 [TestCaseFolderService.lockProjectFolders] 를 잡고 한
     * 트랜잭션에서 저장한다.
     *
     * `gameBuildId` 가 있으면 그 버전에 넣는다(ARTEL-1031). 이 프로젝트 build 가 아니면 404 다.
     */
    suspend fun createTestCase(projectId: Long, userId: Long, request: TestCaseCreateRequest): TestCaseResponse? {
        if (!projectAccessService.isMember(projectId, userId)) return null
        val folderId = parseOptionalWireId(request.folderId, "folderId")
        val gameBuildId = parseOptionalWireId(request.gameBuildId, "gameBuildId")
        if (gameBuildId != null) {
            gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: throw NotFoundException("버전을 찾을 수 없습니다.")
        }
        val entity = TestCaseEntity(
            projectId = projectId,
            scene = request.scene.requireField("scene"),
            step = request.step.requireField("step"),
            precondition = request.precondition?.ifBlank { null },
            expectedValue = request.expectedValue.requireField("expectedValue"),
            verificationStatus = VerificationStatus.DRAFT.name,
            functions = request.functions?.validated(),
        )
        if (folderId == null && gameBuildId == null) return repository.save(entity).toTestCaseResponse()
        val saved = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            folderId?.let { folderService.requireFolder(projectId, it) }
            val created = repository.save(entity)
            val caseId = requireNotNull(created.id)
            folderId?.let { folderItems.append(projectId, it, listOf(caseId)).toList() }
            gameBuildId?.let { manualCases.attachNewCase(projectId, it, caseId) }
            created
        }
        return saved.toTestCaseResponse(folderId)
    }

    /**
     * test case 를 folder 로 옮긴다(ARTEL-969). `folderId` 가 null 이면 folder 없음으로 옮긴다.
     *
     * 케이스가 folder 하나에만 들던 때의 요청이라, 케이스를 **모든 folder 에서 뺀 뒤** `folderId` 끝에 넣는다
     * (ARTEL-1023). folder 사이 옮기기는 [TestCaseFolderItemService.move] 다.
     *
     * 비멤버와 다른 프로젝트 folder 는 404 다. 다른 프로젝트 test case 와 없는 id 는 옮기지 않고 세지
     * 않는다. folder 확인과 `UPDATE` 사이에 folder 가 지워지면 FK 위반(500)이 나므로 folder 쓰기와 같은
     * lock 을 잡는다.
     */
    suspend fun moveTestCases(projectId: Long, userId: Long, request: TestCaseMoveRequest): TestCaseMoveResponse {
        if (!projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        }
        val caseIds = parseWireIds(request.caseIds, "caseIds")
        val folderId = parseOptionalWireId(request.folderId, "folderId")
        val moved = transactionalOperator.executeAndAwait {
            folderService.lockProjectFolders(projectId)
            if (folderId != null) folderService.requireFolder(projectId, folderId)
            folderItems.removeEverywhere(projectId, caseIds)
            if (folderId != null) folderItems.append(projectId, folderId, caseIds).toList()
            repository.countByProjectIdAndIdIn(projectId, caseIds).toInt()
        }
        return TestCaseMoveResponse(moved)
    }

    /**
     * 케이스 단건 조회(프로젝트 참여자만). 없거나 비참여자면 null.
     *
     * 목록과 달리 `evidenceGaps`까지 낸다 — 왜 상세에만 싣는지는 [TestCaseDetailResponse] 참조.
     */
    /**
     * 케이스 단건. [gameBuildId] 를 주면 그 버전으로 비춘다(ARTEL-1069) — TR 화면이 TR 버전의 내용과 판정을 본다.
     * 그 버전에 몫이 없으면 정체 그대로다.
     */
    suspend fun getTestCase(caseId: Long, userId: Long, gameBuildId: Long? = null): TestCaseDetailResponse? {
        val identity = accessible(caseId, userId) ?: return null
        return reader.overlay(identity, CaseScope.of(gameBuildId))
            .toTestCaseDetailResponse(objectMapper, folderItems.findFirstFolderId(caseId))
    }

    /** 케이스 수정. 준 필드만 반영. verificationStatus는 enum 검증. */
    suspend fun updateTestCase(caseId: Long, userId: Long, request: TestCaseUpdateRequest): TestCaseResponse? {
        val existing = accessible(caseId, userId) ?: return null
        val statusName = request.verificationStatus?.let {
            VerificationStatus.fromWire(it)?.name
                ?: throw BadRequestException("verificationStatus must be one of ${VerificationStatus.NAMES}")
        }
        val updated = existing.copy(
            scene = request.scene?.ifBlank { null } ?: existing.scene,
            step = request.step?.ifBlank { null } ?: existing.step,
            precondition = if (request.precondition == null) existing.precondition else request.precondition.ifBlank { null },
            expectedValue = request.expectedValue?.ifBlank { null } ?: existing.expectedValue,
            verificationStatus = statusName ?: existing.verificationStatus,
            functions = request.functions?.validated() ?: existing.functions,
        )
        return repository.save(updated).toTestCaseResponse(folderItems.findFirstFolderId(caseId))
    }

    /** 케이스 삭제(참여자만). 접근 불가면 조용히 no-op(존재 숨김). 조합 정리는 별도. */
    suspend fun deleteTestCase(caseId: Long, userId: Long) {
        accessible(caseId, userId)?.let { repository.delete(it) }
    }

    /**
     * 여러 test case 를 한 번에 지운다(ARTEL-967). 비멤버면 본문과 무관하게 404 다.
     *
     * 시나리오가 `case_id` 로 인용한 케이스도 지운다. 단건 삭제와 같은 규칙이다 — 인용된 케이스를
     * `BROKEN` 으로 돌리는 것은 content map 에서 기능이 사라졌을 때 자동으로 지우는
     * [MapTestCaseWriter] 의 규칙이고, 이것은 사용자가 고른 케이스를 지우는 요청이다.
     */
    suspend fun bulkDeleteTestCases(
        projectId: Long,
        userId: Long,
        request: TestCaseBulkDeleteRequest,
    ): TestCaseBulkDeleteResponse {
        if (!projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        }
        val caseIds = parseWireIds(request.caseIds, "caseIds")
        return TestCaseBulkDeleteResponse(repository.deleteByProjectIdAndIdIn(projectId, caseIds))
    }

    private suspend fun accessible(caseId: Long, userId: Long): TestCaseEntity? {
        val entity = repository.findById(caseId) ?: return null
        return if (projectAccessService.isMember(entity.projectId, userId)) entity else null
    }

    private fun String?.requireField(name: String): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("$name is required")
}
