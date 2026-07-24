package kr.artel.orchestration.referencecontext.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.referencecontext.dto.GameContextPayload
import kr.artel.orchestration.referencecontext.dto.ReferenceContextEntryResponse
import kr.artel.orchestration.referencecontext.dto.ReferenceContextListResponse
import kr.artel.orchestration.referencecontext.entity.ReferenceContextEntity
import kr.artel.orchestration.referencecontext.entity.ReferenceContextType
import kr.artel.orchestration.referencecontext.repository.ReferenceContextRepository
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

/**
 * reference_context 도메인 서비스. 컨트롤러가 얇게 유지되도록 적재/조회 비즈니스 로직을 담당한다.
 *
 * 적재는 **한 문서의 game_context를 타입별로 분해**하여, 그 문서의 기존 타입 행을 교체(멱등)한다.
 * 다른 문서는 건드리지 않고 별개 행으로 공존한다. 조회는 프로젝트 스코프의 타입별로 이루어진다.
 * JSON 직렬화/역직렬화(Json ↔ 구조화)는 여기서 처리한다.
 */
@Service
class ReferenceContextService(
    private val referenceContextRepository: ReferenceContextRepository,
    private val objectMapper: ObjectMapper
) {

    /**
     * 문서에서 추출한 game_context를 타입별로 저장한다. 같은 (projectId, sourceDocumentId)의
     * 기존 행을 먼저 제거한 뒤 새 타입 행을 넣어, 문서 재추출 시 그 문서분만 교체된다(멱등).
     * 빈 섹션(빈 배열/overview 없음)은 저장하지 않는다.
     */
    fun replaceDocumentReferenceContext(
        projectId: Long,
        sourceDocumentId: Long,
        gameContext: GameContextPayload
    ): Mono<Void> {
        val rows = buildTypeRows(projectId, sourceDocumentId, gameContext)
        return referenceContextRepository
            .deleteByProjectIdAndSourceDocumentId(projectId, sourceDocumentId)
            .thenMany(referenceContextRepository.saveAll(rows))
            .then()
    }

    /** 프로젝트의 특정 타입 항목(문서 전반)을 조회한다. Agent가 타입별로 뽑아 참고한다. */
    fun findReferenceContextByType(projectId: Long, type: String): Mono<ReferenceContextListResponse> =
        referenceContextRepository.findByProjectIdAndType(projectId, type)
            .map { toEntryResponse(it) }
            .collectList()
            .map { ReferenceContextListResponse(it) }

    /** game_context를 8개 타입 행으로 분해한다(빈 섹션 제외). content는 각 섹션 값을 JSONB로 직렬화. */
    private fun buildTypeRows(
        projectId: Long,
        sourceDocumentId: Long,
        gameContext: GameContextPayload
    ): List<ReferenceContextEntity> {
        val sections: List<Pair<ReferenceContextType, Any?>> = listOf(
            ReferenceContextType.OVERVIEW to gameContext.overview,
            ReferenceContextType.SCREENS to gameContext.screens,
            ReferenceContextType.MECHANICS to gameContext.mechanics,
            ReferenceContextType.ENTITIES to gameContext.entities,
            ReferenceContextType.PROGRESSION to gameContext.progression,
            ReferenceContextType.FLOWS to gameContext.flows,
            ReferenceContextType.GLOSSARY to gameContext.glossary,
            ReferenceContextType.MISC to gameContext.misc,
        )
        return sections
            .filter { (_, value) -> value.isNotEmptySection() }
            .map { (type, value) ->
                ReferenceContextEntity(
                    projectId = projectId,
                    sourceDocumentId = sourceDocumentId,
                    type = type.key,
                    content = Json.of(objectMapper.writeValueAsString(value))
                )
            }
    }

    /** 빈 리스트 섹션이나 null overview는 저장 대상에서 제외한다. */
    private fun Any?.isNotEmptySection(): Boolean = when (this) {
        null -> false
        is Collection<*> -> isNotEmpty()
        else -> true
    }

    private fun toEntryResponse(entity: ReferenceContextEntity): ReferenceContextEntryResponse =
        ReferenceContextEntryResponse(
            sourceDocumentId = entity.sourceDocumentId,
            type = entity.type,
            content = objectMapper.readTree(entity.content.asString())
        )
}
