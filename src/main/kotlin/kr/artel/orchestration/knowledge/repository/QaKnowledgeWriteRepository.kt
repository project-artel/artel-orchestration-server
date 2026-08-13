package kr.artel.orchestration.knowledge.repository

import kr.artel.orchestration.knowledge.entity.QaKnowledgeWriteEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 지식 쓰기 멱등 원장의 접근 경로(ARTEL-364).
 *
 * 조회는 하나뿐이고 그것으로 충분하다. 원장은 "이 프레임을 이미 적용했나"에만 답하며, 의미 축
 * (대상 id + 타입)으로 찾는 메서드를 여기 더하면 멱등 장치가 런 단위 동작 이력이 된다 — 그것은
 * 다른 문제이고, 그 조회가 생기는 순간 "이미 지워진 항목의 DELETE"와 "없는 id를 지목한 DELETE"를
 * 한 응답으로 뭉개고 싶은 유혹이 따라온다.
 */
interface QaKnowledgeWriteRepository : CoroutineCrudRepository<QaKnowledgeWriteEntity, Long> {

    suspend fun findByQaTryIdAndMessageId(qaTryId: Long, messageId: String): QaKnowledgeWriteEntity?
}
