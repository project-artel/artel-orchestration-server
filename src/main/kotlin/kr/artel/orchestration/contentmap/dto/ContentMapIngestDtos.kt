package kr.artel.orchestration.contentmap.dto

import io.swagger.v3.oas.annotations.media.Schema
import kr.artel.orchestration.contentmap.ingest.BuildIngestOutcome
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
    @Schema(description = "이번에 처리하지 못하고 남은 대기 문서가 있나. 있으면 다시 눌러야 한다")
    val pendingRemaining: Boolean,
) {
    companion object {
        fun of(outcome: BuildIngestOutcome): IngestContentMapResponse {
            val documents = mutableListOf<IngestedDocumentResponse>()
            val failed = mutableListOf<FailedDocumentResponse>()
            // `filterIsInstance` 두 번이 아니라 총망라 `when` 인 이유: 결과 종류가 하나 늘면 저쪽은
            // 조용히 양쪽 목록에서 빠져 응답이 짧아질 뿐이고, 이쪽은 컴파일이 막는다.
            outcome.outcomes.forEach {
                when (it) {
                    is IngestOutcome.Ingested -> documents += IngestedDocumentResponse.of(it)
                    is IngestOutcome.Failed -> failed += FailedDocumentResponse(it.documentId, it.clientMessage)
                }
            }
            return IngestContentMapResponse(documents, failed, outcome.pendingRemaining)
        }
    }
}

/**
 * 문서 하나가 앉은 결과.
 *
 * id 를 `Long` 으로 내는 것은 **같은 컨트롤러의 등록 응답**(`RegisterEvidenceDocumentResponse`)이
 * 그렇게 내기 때문이다. `game` 모듈은 문자열 규약이지만, 한 화면이 같은 `documentId` 를 어떤 요청에서는
 * 숫자로 어떤 요청에서는 문자열로 받는 것이 두 규약 중 어느 쪽을 고르는 것보다 나쁘다.
 */
@Schema(description = "적재된 문서 하나")
data class IngestedDocumentResponse(
    val documentId: Long,
    val contentMapId: Long,
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
                documentId = documentId,
                contentMapId = contentMapId,
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
    val documentId: Long,
    @Schema(description = "사람에게 보여 줄 사유. 내부 예외 메시지는 로그와 문서 행에만 남는다")
    val error: String,
)
