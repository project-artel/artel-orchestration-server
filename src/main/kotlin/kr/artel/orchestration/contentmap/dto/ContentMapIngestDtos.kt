package kr.artel.orchestration.contentmap.dto

import io.swagger.v3.oas.annotations.media.Schema
import kr.artel.orchestration.contentmap.ingest.IngestOutcome

/**
 * 적재 한 번의 결과. 성공과 실패를 **함께** 낸다.
 *
 * 실패를 예외로 올리지 않는 이유: 요청 자체는 정상 처리됐고 결과가 실패인 것이라, 한 문서가 깨졌다고
 * 같이 앉은 나머지 결과까지 버릴 수 없다. `error-handling.md` 가 말하는 "예상된 부분 실패"는 구조화된
 * 본문으로 답한다.
 */
@Schema(description = "근거 문서 적재 결과")
data class IngestContentMapResponse(
    @Schema(description = "앉은 문서")
    val documents: List<IngestedDocumentResponse>,
    @Schema(description = "적재하지 못한 문서와 그 사유")
    val failed: List<FailedDocumentResponse>,
) {
    companion object {
        fun of(outcomes: List<IngestOutcome>) = IngestContentMapResponse(
            documents = outcomes.filterIsInstance<IngestOutcome.Ingested>()
                .map { IngestedDocumentResponse.of(it) },
            failed = outcomes.filterIsInstance<IngestOutcome.Failed>()
                .map { FailedDocumentResponse(it.documentId.toString(), it.error) },
        )
    }
}

/**
 * 문서 하나가 앉은 결과.
 *
 * `IngestResult` 를 그대로 직렬화하지 않고 옮겨 담는 이유는 id 하나 때문이다 — 브라우저용 경로는
 * `game` 모듈처럼 id 를 **문자열**로 낸다. 적재기의 결과 타입은 서버 안에서 `Long` 으로 다니는 것이
 * 맞고, 그 둘을 맞추는 자리가 여기다.
 */
@Schema(description = "적재된 문서 하나")
data class IngestedDocumentResponse(
    val documentId: String,
    val contentMapId: String,
    @Schema(description = "앉은 씬 수")
    val scenes: Int,
    @Schema(description = "앉은 기능 수")
    val capabilities: Int,
    @Schema(description = "같은 명세로 접힌 후보 수. 갑자기 뛰면 키 산식을 볼 차례다")
    val collapsed: Int,
    @Schema(description = "사라져서 지운 기능 수")
    val deleted: Int,
    @Schema(description = "사라졌지만 런타임 참조가 있어 적용 불가로 내린 기능 수")
    val markedNotApplicable: Int,
) {
    companion object {
        fun of(ingested: IngestOutcome.Ingested) = with(ingested.result) {
            IngestedDocumentResponse(
                documentId = documentId.toString(),
                contentMapId = contentMapId.toString(),
                scenes = scenes,
                capabilities = capabilities,
                collapsed = collapsed,
                deleted = deleted,
                markedNotApplicable = markedNotApplicable,
            )
        }
    }
}

/** 적재하지 못한 문서. 사유는 문서 행에도 남아 있어 다음 조회에서 다시 읽을 수 있다. */
@Schema(description = "적재 실패한 문서")
data class FailedDocumentResponse(
    val documentId: String,
    @Schema(description = "실패 사유 한 줄")
    val error: String,
)
