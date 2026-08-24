package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.repository.ScenarioCaseFactRepository
import org.springframework.stereotype.Service

/**
 * "이 케이스는 **무엇으로 이루어져 있나**"에 답한다(ARTEL-466).
 *
 * 저작이 쓰는 검증 스텝이 케이스 제목을 옮겨 적은 문장이 되고, 브리지를 한 줄로 뭉뚱그리는 일이
 * 관측됐다. 원인은 단순하다 — **케이스가 실제로 몇 번의 조작인지, 그 조작을 뭐라고 부르는지 아는
 * 데가 케이스 목록에는 없다.** 지도에는 있다.
 *
 * 두 축으로 찾고 **어느 축으로 찾았는지를 함께 낸다.** 근거 키(`evidence`)로 닿은 것은 그 케이스가
 * 가리키는 코드 자체이고, 값(`supporting_state`)으로 닿은 것은 같은 값을 건드리는 기능이라 여럿일
 * 수 있다. 이 둘을 뭉치면 "정확히 이것"과 "아마 이 중 하나"가 구분되지 않는다.
 *
 * **못 찾는 것이 정상이고, 그것도 답이다.** 실측(word-venture, 케이스 66건 / 기능 18건)에서 근거
 * 키로 13건, 값으로 22건이 닿았다. 나머지는 지도에 그 기능이 아직 없다 — 그때 "모른다"고 답하는
 * 것이 지어낸 조작 이름을 주는 것보다 낫고, 그 목록이 곧 지도 커버리지 구멍이다.
 */
@Service
class ScenarioCaseFactService(
    private val objectMapper: ObjectMapper,
    private val testCaseRepository: TestCaseRepository,
    private val buildRepository: GameBuildRepository,
    private val contentMapRepository: ContentMapRepository,
    private val factRepository: ScenarioCaseFactRepository,
    private val effectRepository: CapabilityEffectRepository,
) {
    /** 케이스 하나를 지도에 비춰 본다. 지도가 없거나 케이스가 남의 것이면 조작 없이 사유만 낸다. */
    suspend fun explain(projectId: Long, appUserId: Long, testCaseId: Long): CaseFacts {
        val case = testCaseRepository.findById(testCaseId)
        if (case == null || case.projectId != projectId) {
            return CaseFacts(testCaseId, note = "이 프로젝트에 없는 케이스다.")
        }

        val scene = ScenarioStateReader.sceneOf(case)
        val stateBefore = ScenarioStateReader.guardsOf(case.precondition)
            .map { CaseGuardView(it.variable, it.operator, it.value) }
        val stateAfter = ScenarioStateReader.stateAfter(case, objectMapper)

        val contentMapId = contentMapIdOf(projectId, appUserId)
            ?: return CaseFacts(
                testCaseId, scene, stateBefore, stateAfter,
                note = "이 프로젝트에는 씬 명세가 아직 없어 조작을 확인할 수 없다.",
            )

        val byEvidence = ScenarioStateReader.evidenceTails(case, objectMapper).flatMap { tail ->
            factRepository.findByEvidenceTail(contentMapId, tail).toList()
        }.distinctBy { it.id }
        val byEffect = supportingVariable(case)?.let { variable ->
            factRepository.findByEffectTarget(contentMapId, variable).toList()
        }.orEmpty().filter { candidate -> byEvidence.none { it.id == candidate.id } }

        val found = byEvidence + byEffect
        val operations = byEvidence.map { view(it, "evidence") } + byEffect.map { view(it, "effect") }
        if (operations.isEmpty()) {
            return CaseFacts(
                testCaseId, scene, stateBefore, stateAfter,
                note = "이 케이스에 대응하는 조작이 씬 명세에 없다. 케이스 문구에 있는 것 외에는 " +
                    "조작 이름을 지어내지 말 것.",
            )
        }

        return CaseFacts(
            testCaseId, scene, stateBefore, stateAfter,
            operations = operations,
            observable = observable(found),
            note = "조작 ${operations.size}건. evidence 로 닿은 것은 이 케이스가 가리키는 코드 자체이고, " +
                "effect 로 닿은 것은 같은 값을 건드리는 기능이라 여럿일 수 있다.",
        )
    }

    /**
     * 기대결과를 실행 중에 **되읽을 수 있나.**
     *
     * 되읽을 수 없는 값을 확인하라고 적으면 실행이 판정 불가로 떨어진다. 지도가 아는 상한이 이
     * 값이므로 그대로 낸다 — 판단은 저작하는 쪽에 남긴다.
     *
     * **[CapabilityEntity.observability] 축을 그대로 읽는다**(ARTEL-479). 효과의 `watchable` 을 우리가
     * 따로 세던 판이 있었는데, 같은 질문에 답이 두 벌이 되면 언젠가 갈라지고 그때 어느 쪽이 맞는지
     * 알 수 없다. 판정하는 축은 지도가 갖는다.
     */
    private fun observable(capabilities: List<CapabilityEntity>): Boolean =
        capabilities.any { it.observability == Observability.OBSERVABLE.wire }

    /** `` `MapMove.position` write `+1` `` 에서 변수 이름만 뽑는다. */
    private fun supportingVariable(case: TestCaseEntity): String? = runCatching {
        val raw = objectMapper.readTree(case.metadata.asString())
            .path("source").path("supporting_state").asText(null) ?: return null
        SUPPORTING.find(raw)?.groupValues?.get(1)?.let { ScenarioStateReader.normalize(it) }
    }.getOrNull()

    private fun view(capability: CapabilityEntity, matchedBy: String) = CaseOperation(
        capabilityId = capability.id!!,
        // **누를 것이 없으면 빈 값이다.** 예전에는 `interaction` 을 그대로 넣어 조작 없이 일어나는
        // 기능이 `input: "none"` 으로 스텝에 박혔다 — 실행하는 쪽에서 보면 누르라는 뜻으로 읽힌다.
        input = when {
            capability.inputKey != null -> "key:${capability.inputKey}"
            capability.controlPath != null -> "click:${capability.controlPath}"
            capability.controlLabel != null -> "click:${capability.controlLabel}"
            else -> ""
        },
        label = capability.controlLabel ?: capability.controlPath,
        summary = capability.summary,
        given = capability.givenText,
        actionability = capability.actionability,
        matchedBy = matchedBy,
    )

    private suspend fun contentMapIdOf(projectId: Long, appUserId: Long): Long? {
        val build = buildRepository.findAllForMember(projectId, appUserId).firstOrNull() ?: return null
        return contentMapRepository.findByGameBuildIdOrderByIdDesc(build.id!!).firstOrNull()?.id
    }

    private companion object {
        val SUPPORTING = Regex("""`([^`]+)`""")
    }
}

/**
 * 케이스 하나를 지도에 비춰 본 것.
 *
 * @property operations 이 케이스가 가리키는 조작들. **비어 있을 수 있고 그것도 답이다** — 지도가
 *   아직 그 기능을 모른다는 뜻이라, 조작 이름을 지어내지 말라는 신호가 된다.
 * @property observable 기대결과를 실행 중에 되읽을 수 있나. 조작을 못 찾았으면 null(모름).
 */
data class CaseFacts(
    val testCaseId: Long,
    val scene: String? = null,
    val stateBefore: List<CaseGuardView> = emptyList(),
    val stateAfter: Map<String, String> = emptyMap(),
    val operations: List<CaseOperation> = emptyList(),
    val observable: Boolean? = null,
    val note: String = "",
)

/** 비교 하나. 변수명은 마지막 마디로 통일한다. */
data class CaseGuardView(val variable: String, val operator: String, val value: String)

/**
 * @property input 실행하는 쪽이 그대로 쓰는 값(`key:Return`·`click:경로`).
 * @property actionability 실행 축(ARTEL-479). `status` 가 아니라 이것을 낸다 — 관측 불가까지 섞인
 *   파생값은 "이 조작을 할 수 있는가"라는 이 자리의 질문에 답하지 못한다.
 * @property matchedBy `evidence` 는 그 케이스가 가리키는 코드 자체, `effect` 는 같은 값을 건드리는 기능.
 */
data class CaseOperation(
    val capabilityId: Long,
    val input: String,
    val label: String?,
    val summary: String,
    val given: String?,
    val actionability: String,
    val matchedBy: String,
)
