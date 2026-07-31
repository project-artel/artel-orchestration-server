package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddingSource
import kr.artel.orchestration.common.embedding.EmbeddingSourceResult
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.springframework.stereotype.Component

/**
 * test_case 백필의 도메인 소싱(ARTEL-216). 골격(seed/claim/embed/store)은 공용
 * [kr.artel.orchestration.common.embedding.EmbeddingBackfillWorker]가 담당하고, 여기서는 도메인 특정
 * 부분만 한다: 케이스를 로드해 본문을 합성한다.
 *
 * knowledge와 달리 검색쿼리를 생성하지 않는다(케이스는 짧고 자기서술적이라 CONTENT 1벡터로 시작).
 * 그래서 Agent `/knowledge-queries` 없이 `/embed`만 탄다. 대상 케이스가 사라졌으면 orphan으로
 * 넘겨 워커가 큐에서 버리게 한다. 로컬 합성이라 소싱 자체의 실패는 없다.
 */
@Component
class TestCaseEmbeddingSource(
    private val testCaseRepository: TestCaseRepository,
) : EmbeddingSource {

    override suspend fun sourceTexts(claimed: List<ClaimedRow>): EmbeddingSourceResult {
        val byId = testCaseRepository.findAllById(claimed.map { it.ownerId })
            .toList()
            .associateBy { requireNotNull(it.id) }

        val orphanedPendingIds = claimed.filter { it.ownerId !in byId }.map { it.pendingId }.toSet()
        val textsByOwnerId = claimed.mapNotNull { row ->
            byId[row.ownerId]?.let { row.ownerId to listOf(composeContent(it)) }
        }.toMap()

        return EmbeddingSourceResult(textsByOwnerId, orphanedPendingIds, emptyMap())
    }

    /**
     * 케이스를 임베딩할 CONTENT 텍스트로 합성한다. 검색은 사용자의 자연어 의도를 이 본문에 의미적으로
     * 매칭하므로, 분류·제목·사전조건·기대결과를 모두 담아 검색 표면을 넓힌다.
     */
    private fun composeContent(case: TestCaseEntity): String = buildString {
        append(case.category).append('\n')
        append(case.title)
        case.precondition?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        append('\n').append(case.expected)
    }
}
