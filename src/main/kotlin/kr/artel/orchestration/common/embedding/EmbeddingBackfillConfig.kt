package kr.artel.orchestration.common.embedding

/**
 * [EmbeddingBackfillWorker]가 한 tick에 필요로 하는 설정. 도메인별 `@ConfigurationProperties`가 구현한다.
 *
 * (enabled·interval 같은 스케줄 설정은 워커가 아니라 도메인 스케줄러의 몫이라 여기 두지 않는다.)
 */
interface EmbeddingBackfillConfig {
    /** 벡터를 만들 모델 slug. 임베딩 테이블의 model 파티션 키이자 재색인 단위. */
    val model: String

    /** 한 tick에 처리할 소유 행 수 상한. */
    val batchSize: Int

    /** 한 항목을 몇 번까지 시도할지. 넘긴 항목은 큐 조회에서 빠진다(행은 last_error와 함께 남는다). */
    val maxAttempts: Int
}
