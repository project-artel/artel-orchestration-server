package kr.artel.orchestration.common.embedding

/**
 * 임베딩 큐/저장 연산(도메인 무관). 구현은 [EmbeddingQueueRepository]가 SQL로 제공한다.
 *
 * 인터페이스로 두는 이유: 도메인 리포지토리(`@Repository`)는 예외 변환 CGLIB 프록시로 감싸이는데,
 * 프록시는 **상속된 final 메서드를 가로채지 못한다**(생성자를 거치지 않은 프록시 인스턴스에서 실행되어
 * 필드가 null이 된다). 그래서 도메인 리포지토리는 공용 구현을 상속하지 않고 이 인터페이스를 위임
 * 구현하며, 백필 워커는 이 인터페이스에만 의존한다.
 */
interface EmbeddingQueue {

    suspend fun seedPending(kind: String, model: String, limit: Int): Long

    suspend fun claimPending(kind: String, model: String, maxAttempts: Int, limit: Int): List<ClaimedRow>

    suspend fun replacePendingWithVectors(
        pendingId: Long,
        ownerId: Long,
        kind: String,
        model: String,
        vectors: List<EmbeddedText>,
    )

    suspend fun recordFailure(pendingId: Long, error: String)

    suspend fun discardFor(ownerId: Long): Long

    suspend fun deletePending(pendingId: Long)
}
