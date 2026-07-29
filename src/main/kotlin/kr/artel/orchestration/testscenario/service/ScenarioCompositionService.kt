package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.dto.ScenarioCaseItem
import kr.artel.orchestration.testscenario.dto.ScenarioCasesResponse
import kr.artel.orchestration.testscenario.entity.TestScenarioCaseEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * 시나리오 ↔ 케이스 조합(junction) 서비스. FE 캔버스가 시나리오를 케이스로 구성/재정렬할 때 쓴다.
 *
 * 조회는 조합 행을 순서대로 읽고 케이스 내용을 리졸브한다(한 번의 findAllById로 N+1 회피).
 * 교체(setCases)는 전체 삭제 후 재삽입을 한 트랜잭션으로 처리한다(원자적). 접근은 시나리오의 프로젝트
 * 참여로 인가하며, 조합에 넣는 케이스는 같은 프로젝트 소속이어야 한다(교차참조 방지).
 */
@Service
class ScenarioCompositionService(
    private val accessService: TestScenarioAccessService,
    private val scenarioCaseRepository: TestScenarioCaseRepository,
    private val testCaseRepository: TestCaseRepository,
    private val transactionalOperator: TransactionalOperator,
) {
    /** 시나리오의 케이스 조합을 순서대로 조회(케이스 내용 포함). 비접근이면 빈 Mono. */
    fun getCases(testScenarioId: Long, userId: Long): Mono<ScenarioCasesResponse> =
        accessService.accessibleScenario(testScenarioId, userId)
            .flatMap { resolveCases(testScenarioId) }

    /** 조합 전체 교체. caseIds 순서 = position. 원자적. 반환은 리졸브된 새 조합. */
    fun setCases(testScenarioId: Long, userId: Long, caseIds: List<Long>): Mono<ScenarioCasesResponse> =
        accessService.accessibleScenario(testScenarioId, userId)
            .flatMap { scenario ->
                validateCases(scenario.projectId, caseIds)
                    .then(replaceComposition(testScenarioId, caseIds).`as`(transactionalOperator::transactional).then())
                    .then(resolveCases(testScenarioId))
            }

    private fun replaceComposition(testScenarioId: Long, caseIds: List<Long>): Mono<Void> {
        val rows = caseIds.mapIndexed { index, caseId ->
            TestScenarioCaseEntity(testScenarioId = testScenarioId, testCaseId = caseId, position = index)
        }
        return scenarioCaseRepository.deleteByTestScenarioId(testScenarioId)
            .thenMany(scenarioCaseRepository.saveAll(rows))
            .then()
    }

    /** 넣으려는 케이스가 모두 존재하고 같은 프로젝트인지 확인. 아니면 400. */
    private fun validateCases(projectId: Long, caseIds: List<Long>): Mono<Void> {
        if (caseIds.isEmpty()) return Mono.empty()
        val distinct = caseIds.toSet()
        return testCaseRepository.findAllById(distinct).collectList().flatMap { cases ->
            when {
                cases.size != distinct.size ->
                    Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "some test cases were not found"))
                cases.any { it.projectId != projectId } ->
                    Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "a test case belongs to another project"))
                else -> Mono.empty()
            }
        }
    }

    private fun resolveCases(testScenarioId: Long): Mono<ScenarioCasesResponse> =
        scenarioCaseRepository.findByTestScenarioIdOrderByPosition(testScenarioId)
            .collectList()
            .flatMap { rows ->
                if (rows.isEmpty()) {
                    Mono.just(ScenarioCasesResponse(testScenarioId.toString(), emptyList()))
                } else {
                    testCaseRepository.findAllById(rows.map { it.testCaseId })
                        .collectMap({ requireNotNull(it.id) }, { it })
                        .map { caseById ->
                            val items = rows.mapNotNull { row ->
                                caseById[row.testCaseId]?.let { ScenarioCaseItem(row.position, it.toTestCaseResponse()) }
                            }
                            ScenarioCasesResponse(testScenarioId.toString(), items)
                        }
                }
            }
}
