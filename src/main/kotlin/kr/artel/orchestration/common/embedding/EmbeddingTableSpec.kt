package kr.artel.orchestration.common.embedding

/**
 * 한 도메인의 임베딩 큐/저장 테이블 좌표. [EmbeddingQueueRepository]의 SQL이 이 값으로 조립된다.
 *
 * 값은 전부 코드가 주는 상수다(사용자 입력이 아니다). 그래서 식별자를 바인드 파라미터가 아니라
 * 문자열로 SQL에 끼워 넣어도 인젝션 위험이 없다 — 애초에 R2DBC는 테이블/컬럼명 바인딩을 지원하지 않는다.
 */
data class EmbeddingTableSpec(
    /** 임베딩 큐/벡터 테이블 이름. 예: `knowledge_embedding`. */
    val embeddingTable: String,
    /** 소유 행을 가리키는 FK 컬럼. 예: `knowledge_id`. */
    val ownerIdColumn: String,
    /** 소유 테이블 이름. `seedPending`이 여기서 아직 임베딩되지 않은 행을 고른다. 예: `knowledge`. */
    val ownerTable: String,
    /**
     * 소유 테이블에서 "살아있는" 행만 시딩 대상으로 삼는 조건. 컬럼 이름만 담은 술어이며(별칭 없이,
     * 예: `deleted_at IS NULL`) 리포지토리가 소유 테이블 별칭을 붙인다. 소프트삭제가 없는 도메인은
     * null — 그 테이블 전체가 대상이 된다.
     */
    val aliveClause: String? = null,
)
