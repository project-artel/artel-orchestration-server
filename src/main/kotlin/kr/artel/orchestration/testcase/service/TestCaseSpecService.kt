package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.xlsx.SpecXlsxWriter
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.dto.TestCaseSpecDownloadResponse
import kr.artel.orchestration.testcase.dto.TestCaseSpecEntry
import kr.artel.orchestration.testcase.dto.TestCaseSpecEnvelope
import kr.artel.orchestration.testcase.dto.TestCaseSpecIngestResult
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseEmbeddingRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * Agent가 SDK 등록 시점에 보내는 **기능 테스트 명세 JSON**을 받아 두 가지를 한 흐름에서 처리한다.
 *
 * 1. **XLSX로 변환해 S3에 저장** — 사용자가 명세를 엑셀로 내려받아 공유·검토할 수 있게.
 * 2. **`test_case`로 적재** — 저작 Agent가 들고 다닐 케이스 라이브러리를 채운다.
 *
 * **원문 JSON은 저장하지 않는다.** 파싱해서 위 둘을 만들고 버린다. 남는 것은 XLSX 한 장과
 * `test_case` 행들뿐이며, 봉투에서 취하는 것도 값 둘(`revision` → `spec_revision`,
 * `created_at` → `source_sent_at`)뿐이다. 원문을 따로 보관하면 "무엇이 진실인가"가 둘이 된다.
 *
 * 순서가 중요하다. **S3 저장이 마지막이다**: 앞 단계가 실패했는데 버킷에만 파일이 남는 고아 상태와,
 * DB와 산출물이 어긋난 채 다운로드되는 상태를 막는다. 자세한 근거는 [ingest] 참조.
 *
 * (2026-08-11, ARTEL-329) 입력이 CSV에서 JSON으로 바뀌었다. 열 이름을 추측하던 자리가 사라지고
 * 계약이 타입으로 고정됐다.
 */
@Service
class TestCaseSpecService(
    private val objectMapper: ObjectMapper,
    private val xlsxWriter: SpecXlsxWriter,
    private val storage: DocumentStorage,
    private val repository: TestCaseRepository,
    private val projectAccessService: ProjectAccessService,
    private val projectRepository: ProjectRepository,
    private val transactionalOperator: TransactionalOperator,
    private val embeddingRepository: TestCaseEmbeddingRepository,
) {

    /**
     * 명세를 적재하고 XLSX 산출물을 저장한다.
     *
     * **오프로딩 경계는 여기 한 곳뿐이다.** JSON 파싱과 XLSX 생성은 블로킹 CPU 작업이라 이벤트
     * 루프(reactor-http-nio)에서 돌면 그 스레드에 걸린 무관한 요청이 전부 멈춘다. 아래 컴포넌트들은
     * 평범한 블로킹 함수로 두고 이 `withContext` 하나로 감싼다 — 각자 감싸면 한 요청에 스레드 홉이
     * 여러 번 늘고, 어디서 이벤트 루프를 벗어나는지 코드에서 보이지 않게 된다.
     *
     * DB(R2DBC)와 S3(비동기 클라이언트)는 블로킹이 아니라 이 블록 안에서도 스레드를 붙잡지 않는다.
     *
     * **순서가 계약이다: 검증 → 변환 → DB 커밋 → S3 저장.**
     * S3를 마지막에 두어야 앞 단계가 실패했을 때 고아 객체가 남지 않는다. 남는 실패 모드는
     * "DB는 최신인데 XLSX만 이전 판"뿐이고, 키가 프로젝트별 고정값이라 재전송이 덮어쓰기로 복구한다.
     */
    suspend fun ingest(projectId: Long, body: ByteArray): TestCaseSpecIngestResult =
        withContext(Dispatchers.IO) {
            // 없는(또는 삭제된) 프로젝트면 여기서 멈춘다. 이 경로에는 엔드유저가 없어 멤버십으로
            // 걸러낼 수 없고, 확인하지 않으면 오타 난 projectId로 보낸 케이스가 아무도 볼 수 없는
            // 곳에 조용히 쌓인다.
            projectRepository.findActiveById(projectId) ?: throw NotFoundException()

            val envelope = parse(body)
            requireCases(envelope)

            // 같은 판이 다시 왔으면 아무것도 하지 않는다. SDK 재등록마다 같은 명세가 다시 오는데,
            // 그때마다 수백 행을 upsert하고 XLSX를 다시 써서 S3에 올릴 이유가 없다.
            // revision이 없는 봉투(구버전)는 판단 근거가 없으므로 그냥 진행한다.
            if (envelope.revision != null && isSameRevision(projectId, envelope.revision)) {
                logger.info(
                    "TestCase 명세 재전송 스킵 projectId={} revision={} cases={}",
                    projectId, envelope.revision, envelope.cases.size
                )
                return@withContext TestCaseSpecIngestResult(
                    totalCases = envelope.cases.size,
                    created = 0, updated = 0, skipped = 0, unchanged = true,
                )
            }

            val xlsx = xlsxWriter.write(envelope.cases)
            val result = upsertCases(projectId, envelope)
            storage.put(objectKeyFor(projectId), xlsx, SpecXlsxWriter.XLSX_CONTENT_TYPE)
                .awaitSingleOrNull()
            result
        }

    /**
     * 명세 XLSX 다운로드 티켓. 아직 받은 명세가 없거나 비참여자면 null(→404).
     *
     * URL은 요청할 때마다 새로 만든다. 오래 사는 링크를 화면에 박아두지 않기 위해서다.
     */
    suspend fun downloadTicket(projectId: Long, userId: Long): TestCaseSpecDownloadResponse? {
        if (!projectAccessService.isMember(projectId, userId)) return null
        val objectKey = objectKeyFor(projectId)
        storage.head(objectKey).awaitSingleOrNull() ?: return null
        val presigned = storage.presignDownload(objectKey, DOWNLOAD_FILE_NAME)
        return TestCaseSpecDownloadResponse(presigned.url, presigned.expiresAt)
    }

    /**
     * 파싱 실패는 400이다. 여기서 막지 않으면 케이스가 0건인 성공 응답이 나가고, 보낸 쪽은
     * 반영된 줄 안다 — CSV 시절 열 이름을 못 알아봤을 때와 같은 실패 모양이다.
     */
    private fun parse(body: ByteArray): TestCaseSpecEnvelope =
        try {
            objectMapper.readValue(body, TestCaseSpecEnvelope::class.java)
        } catch (e: Exception) {
            throw BadRequestException("명세 JSON을 읽을 수 없습니다: ${e.message}")
        }

    /**
     * 케이스가 0건인 명세는 거부한다. 전량 덮어쓰기 성격의 적재라, 빈 배열을 통과시키면
     * "보낼 게 없었다"와 "직렬화가 깨져 배열이 비었다"가 같은 결과(아무것도 안 함)로 보인다.
     */
    private fun requireCases(envelope: TestCaseSpecEnvelope) {
        if (envelope.cases.isEmpty()) {
            throw BadRequestException("명세에 케이스가 없습니다(cases 배열이 비어 있음).")
        }
    }

    /** 이 프로젝트가 이미 이 판을 받아 뒀는가. 한 건이라도 그 revision이면 같은 판이다. */
    private suspend fun isSameRevision(projectId: Long, revision: Int): Boolean =
        repository.existsByProjectIdAndSpecRevision(projectId, revision)

    /**
     * 케이스 전체를 한 트랜잭션으로 반영한다. 중간에 실패했을 때 절반만 들어간 라이브러리를 남기면,
     * 재전송이 그 절반을 갱신으로 처리해 무엇이 반영됐는지 아무도 알 수 없게 된다.
     *
     * **멱등 키는 `spec_id`다.** 옛 방식(씬+스텝)은 문구를 한 글자 다듬은 재전송이 새 케이스를
     * 만들어 라이브러리가 계속 늘었다. `spec_id`가 없는 명세(구버전)만 씬+스텝으로 되돌린다.
     *
     * `verificationStatus`·`lastVerifiedBuildId`는 **건드리지 않는다** — 그 값은 명세가 아니라
     * QA 런이 만든 결과라, 재적재로 덮으면 검증 이력이 사라진다.
     */
    private suspend fun upsertCases(
        projectId: Long,
        envelope: TestCaseSpecEnvelope,
    ): TestCaseSpecIngestResult {
        var created = 0
        var updated = 0

        // 같은 배열 안에 같은 식별자가 두 번 나오면 마지막 것만 남긴다. 그대로 두면 한 번의
        // 적재에서 같은 케이스를 만들었다가 곧바로 갱신하는 셈이 된다.
        val parsed = envelope.cases
            .mapNotNull { parseEntry(it) }
            .associateBy { it.identity }
            .values
        val skipped = envelope.cases.size - parsed.size

        transactionalOperator.executeAndAwait {
            parsed.forEach { row ->
                val existing = row.specId?.let { repository.findByProjectIdAndSpecId(projectId, it) }
                    ?: repository.findByProjectIdAndSceneAndStep(projectId, row.scene, row.step)

                if (existing == null) {
                    // 신규 케이스의 임베딩은 여기서 만들지 않는다 — 백필 워커의 주기 seedPending이
                    // 아직 벡터 없는 케이스를 자동으로 픽업한다(적재를 LLM 호출로 늦추지 않는다).
                    repository.save(row.toEntity(projectId, envelope))
                    created++
                } else {
                    // 임베딩 본문은 씬/스텝/사전조건/기대결과로 합성된다. 그중 하나라도 바뀌었을 때만
                    // 옛 벡터를 버려 다음 백필 tick이 새 본문으로 재임베딩하게 한다.
                    val contentChanged = existing.scene != row.scene ||
                        existing.step != row.step ||
                        existing.precondition != row.precondition ||
                        existing.expectedValue != row.expectedValue
                    repository.save(row.applyTo(existing, envelope))
                    if (contentChanged) embeddingRepository.discardFor(existing.id!!)
                    updated++
                }
            }
        }

        logger.info(
            "TestCase 명세 적재 projectId={} revision={} cases={} created={} updated={} skipped={}",
            projectId, envelope.revision, envelope.cases.size, created, updated, skipped
        )
        return TestCaseSpecIngestResult(
            totalCases = envelope.cases.size,
            created = created,
            updated = updated,
            skipped = skipped,
        )
    }

    /**
     * 필수값(씬/스텝/기대결과)이 없는 케이스는 만들 수 없으므로 버린다(전체 실패로 만들지 않는다).
     * 한 건이 잘못돼서 나머지 수백 건이 반영되지 않으면 보낸 쪽이 할 수 있는 일이 없다.
     */
    private fun parseEntry(entry: TestCaseSpecEntry): ParsedCase? {
        val scene = entry.spec.scene?.takeIf { it.isNotBlank() } ?: return null
        val step = entry.spec.step?.takeIf { it.isNotBlank() } ?: return null
        val expectedValue = entry.spec.expectedValue?.takeIf { it.isNotBlank() } ?: return null
        return ParsedCase(
            specId = entry.metadata?.path("source")?.path("spec_id")?.takeIf { it.isTextual }?.asText(),
            schemaVersion = entry.schemaVersion,
            scene = scene,
            step = step,
            precondition = entry.spec.precondition?.takeIf { it.isNotBlank() },
            expectedValue = expectedValue,
            status = entry.spec.status?.takeIf { it.isNotBlank() },
            metadata = entry.metadata?.let { Json.of(objectMapper.writeValueAsBytes(it)) }
                ?: Json.of("{}"),
        )
    }

    private data class ParsedCase(
        val specId: String?,
        val schemaVersion: String?,
        val scene: String,
        val step: String,
        val precondition: String?,
        val expectedValue: String,
        val status: String?,
        val metadata: Json,
    ) {
        /** 같은 배열 안의 중복을 접는 키. spec_id가 있으면 그것이, 없으면 씬+스텝이 정체성이다. */
        val identity: String get() = specId ?: "$scene $step"

        fun toEntity(projectId: Long, envelope: TestCaseSpecEnvelope) = TestCaseEntity(
            projectId = projectId,
            scene = scene,
            step = step,
            precondition = precondition,
            expectedValue = expectedValue,
            status = status,
            schemaVersion = schemaVersion,
            metadata = metadata,
            specId = specId,
            specRevision = envelope.revision,
            sourceSentAt = envelope.createdAt,
        )

        /** 갱신. verificationStatus/lastVerifiedBuildId는 의도적으로 그대로 둔다(QA 런의 결과). */
        fun applyTo(existing: TestCaseEntity, envelope: TestCaseSpecEnvelope) = existing.copy(
            scene = scene,
            step = step,
            precondition = precondition,
            expectedValue = expectedValue,
            status = status,
            schemaVersion = schemaVersion,
            metadata = metadata,
            // spec_id는 한 번 붙으면 유지한다. 새 명세가 값을 안 줘도(구버전) 이미 이어 둔 정체성을
            // 버리면 다음 적재가 같은 케이스를 새로 만든다.
            specId = specId ?: existing.specId,
            specRevision = envelope.revision ?: existing.specRevision,
            sourceSentAt = envelope.createdAt ?: existing.sourceSentAt,
        )
    }

    /**
     * 프로젝트당 한 벌만 둔다. 최신 명세가 곧 현재 상태이고, 적재도 upsert라 이전 판을 되돌아볼
     * 일이 없다. 키가 고정이라 어디에 무엇이 있는지 DB에 따로 기록할 필요도 없다.
     */
    private fun objectKeyFor(projectId: Long) = "projects/$projectId/test-case-spec/test-cases.xlsx"

    companion object {
        private val logger = LoggerFactory.getLogger(TestCaseSpecService::class.java)

        /** 사용자가 받게 될 파일 이름. 브라우저가 저장소 키 대신 이 이름으로 저장한다. */
        const val DOWNLOAD_FILE_NAME: String = "테스트케이스명세.xlsx"
    }
}
