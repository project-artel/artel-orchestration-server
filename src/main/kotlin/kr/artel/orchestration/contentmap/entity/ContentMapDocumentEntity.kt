package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 근거 문서 원본의 포인터. 문서 자체는 스토리지에 있다.
 *
 * DB 에 넣지 않는 이유가 셋이다 — 실측 1,413 KB 로 크고, 적재하고 나면 조회할 일이 없고, 그런데
 * **버려도 안 된다.** 적재기를 고치면 재적재해야 하는데 그때 게임을 다시 돌릴 수는 없다.
 *
 * [contentHash] 로 같은 문서의 재전송을 가린다. 내용이 다르면 새 행이고, 그 이력이 "이 빌드의
 * 근거가 언제 어떻게 달라졌나"가 된다.
 */
@Table("content_map_document")
data class ContentMapDocumentEntity(
    @Id
    val id: Long? = null,

    @Column("content_map_id")
    val contentMapId: Long,

    @Column("object_key")
    val objectKey: String,

    /** 원본 바이트의 SHA-256(소문자 hex). 스토리지가 스트리밍으로 계산한다. */
    @Column("content_hash")
    val contentHash: String,

    @Column("byte_size")
    val byteSize: Long,

    /**
     * 어느 버전의 적재기가 처리했나. null 이면 아직 적재되지 않았다는 뜻이고 ARTEL-442 가 채운다.
     *
     * 버전을 남기는 것은 적재 규칙을 고쳤을 때 **낡은 것부터 다시 돌리기** 위해서다.
     */
    @Column("ingested_by")
    val ingestedBy: String? = null,

    @Column("ingested_at")
    val ingestedAt: Instant? = null,

    /**
     * 적재를 몇 번 시도했나. 실패할 때가 아니라 **집을 때** 오른다.
     *
     * 실패 시점에만 올리면 적재기를 죽이는 문서는 시도가 한 번도 기록되지 않아 상한에 닿지
     * 못한다 — 장부를 달아 놓고도 그 문서만은 무한 재시도가 남는다. 1.4 MB 파싱은 프로세스를
     * 죽일 수 있는 종류의 일이라 이 구멍이 가정이 아니다.
     */
    @Column("ingest_attempts")
    val ingestAttempts: Int = 0,

    /** 마지막 실패 사유. 로그는 돌지만 행은 남는다. */
    @Column("last_error")
    val lastError: String? = null,

    @Column("received_at")
    val receivedAt: Instant? = null,
)
