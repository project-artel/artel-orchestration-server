package kr.artel.orchestration.contentmap.scan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.ingest.IngestOutcome
import kr.artel.orchestration.game.repository.GameInstanceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * `ACTION_RESULT` 중 **근거 스캔의 결과만** 집어 적재까지 잇는다.
 *
 * 지금까지 `ACTION_RESULT` 는 전부 QA 브리지로 갔다. 스캔 결과를 이쪽으로 데려오되 QA 를 깨뜨리면
 * 안 되므로, **가르는 축을 액션 이름 하나로 둔다.**
 *
 * id 로 가르지 않는 이유: 우리에게는 `qa_log` 같은 id 발급처가 없다. 별도 카운터를 두면 그 값이
 * `qa_log` id 와 겹칠 수 있고, 겹치는 순간 QA 결과가 스캔으로 잘못 샌다. 액션 이름은 우리가 보낸
 * 액션에만 실리고, QA 가 보내는 액션의 method 는 agent 가 정하며 `scan_evidence` 가 아니다.
 *
 * [handle] 은 **스캔 결과가 아닌 모든 프레임에 대해 `false` 이고 아무것도 하지 않는다.** 파싱
 * 실패조차 `false` 다 — 이 분기가 QA 쪽 동작을 한 글자도 바꾸지 않게 하려는 것이다.
 */
@Service
class ScanResultRouter(
    private val gameInstances: GameInstanceRepository,
    private val ingest: ContentMapIngestService,
    private val statuses: ScanStatusRegistry,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScanResultRouter::class.java)

    /**
     * @return 이 프레임이 근거 스캔의 결과여서 여기서 처리했으면 true. 그 밖에는 전부 false 이고,
     *   호출자가 지금까지 하던 대로 QA 브리지로 넘긴다.
     */
    suspend fun handle(gameInstanceId: Long, payloadText: String): Boolean {
        val payload = runCatching { objectMapper.readTree(payloadText) }.getOrNull() ?: return false
        val result = scanResultOf(payload) ?: return false

        // 인스턴스가 마지막으로 보고한 빌드가 곧 SDK 가 문서를 올린 빌드다. 우리가 인스턴스를 고른
        // 기준도 그 칸이었으므로 둘은 구성상 같은 값이다.
        val gameBuildId = gameInstances.findById(gameInstanceId)?.lastGameBuildId
        if (gameBuildId == null) {
            // 등록 전이라 앉힐 곳을 모른다. 결과를 버리지 않고 상태로 남겨 화면이 이유를 말하게 한다.
            logger.warn("스캔 결과를 받았으나 인스턴스가 보고한 빌드가 없다 [gameInstanceId={}]", gameInstanceId)
            fail(gameInstanceId, NO_BUILD)
            return true
        }

        if (!result.path(SUCCESS_FIELD).asBoolean(false)) {
            // 게임이 스캔에 실패했다고 답했다. 올라온 문서가 없으니 문서 행에 적을 것이 없다 —
            // 이 자리가 그 사실을 남길 유일한 곳이다.
            val reason = sdkErrorOf(result)
            logger.warn("근거 스캔 실패 [gameBuildId={}]: {}", gameBuildId, reason)
            statuses.complete(gameBuildId) {
                it.copy(state = ScanState.FAILED, finishedAt = Instant.now(clock), error = reason)
            }
            return true
        }

        ingestFor(gameBuildId)
        return true
    }

    /**
     * 프레임에서 근거 스캔 결과 항목을 골라낸다. 없으면 null 이고, 그때 이 프레임은 우리 것이 아니다.
     *
     * **`results[]` 를 먼저 봐야 한다.** SDK 의 `ACTION_RESULT` 는 액션 하나가 아니라 **여러 결과를
     * 배열로** 싣는다 — 실측 프레임이 그렇다:
     *
     * ```
     * {"type":"ACTION_RESULT","id":12,"requestId":1,
     *  "results":[{"id":1,"success":true,"action":"scan_evidence","returnValue":{...}}]}
     * ```
     *
     * 최상위에서 `action` 을 찾으면 없다. 처음 이 클래스는 그렇게 찾았고, 그래서 스캔 결과가 조용히
     * QA 브리지로 흘러가 **적재가 한 번도 돌지 않았다.** 로그에는 "액션 결과 수신"만 남고 오류는
     * 없었다 — 문서는 등록되는데 씬·기능 행이 0인 상태가 된다.
     *
     * 최상위도 함께 보는 이유는 방어다. 프레임이 한 결과만 평평하게 싣는 모양으로 바뀌어도 이쪽이
     * 조용히 멎지 않는다.
     */
    private fun scanResultOf(payload: JsonNode): JsonNode? {
        if (payload.path(ACTION_FIELD).asText(null) == ContentMapScanService.SCAN_EVIDENCE) {
            return payload
        }
        val results = payload.path(RESULTS_FIELD)
        if (!results.isArray) return null
        return results.firstOrNull {
            it.path(ACTION_FIELD).asText(null) == ContentMapScanService.SCAN_EVIDENCE
        }
    }

    /**
     * 이 빌드의 대기 문서를 앉힌다.
     *
     * **이 프레임을 처리하는 코루틴 안에서 그대로 돈다.** `SdkWebSocketHandler` 가 `concatMap` 이라
     * 그동안 그 세션의 다음 프레임이 기다린다 — 실측 1.4MB 문서를 파싱하는 만큼이다. 사람이 눌러야
     * 생기는 드문 일이고, 떼어내면 순서와 오류 귀속이 흐려진다. 문제가 되면 답은 큐이지 이 자리가
     * 아니다.
     */
    private suspend fun ingestFor(gameBuildId: Long) {
        val outcomes = try {
            ingest.ingestBuild(gameBuildId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 문서마다의 실패는 ingestBuild 가 이미 문서에 적는다. 여기까지 온 것은 문서를 고르는
            // 일 자체가 깨진 경우다. 원문은 로그에만 남기고 화면에는 우리 문구를 낸다.
            logger.error("근거 스캔 결과 적재 실패 [gameBuildId={}]", gameBuildId, failure)
            fail(gameBuildId, GENERIC_INGEST_FAILURE)
            return
        }

        val failed = outcomes.filterIsInstance<IngestOutcome.Failed>()
        val ingested = outcomes.size - failed.size
        statuses.complete(gameBuildId) { current ->
            if (failed.isEmpty()) {
                current.copy(
                    state = ScanState.SUCCEEDED,
                    finishedAt = Instant.now(clock),
                    ingestedDocuments = ingested,
                )
            } else {
                // 깨진 문서의 사유는 이미 ingest_error 에 durable 하게 남았다. 여기서는 화면이
                // 곧바로 볼 수 있게 첫 사유 하나만 되풀이한다.
                current.copy(
                    state = ScanState.FAILED,
                    finishedAt = Instant.now(clock),
                    ingestedDocuments = ingested,
                    error = failed.first().reason,
                )
            }
        }
        logger.info("근거 스캔 적재 완료 [gameBuildId={}, 앉힘={}, 깨짐={}]", gameBuildId, ingested, failed.size)
    }

    private fun fail(gameBuildId: Long, reason: String) {
        statuses.complete(gameBuildId) {
            it.copy(state = ScanState.FAILED, finishedAt = Instant.now(clock), error = reason)
        }
    }

    /**
     * 게임이 준 실패 사유.
     *
     * 이것은 **게임 클라이언트가 쓴 문장이지 서버 예외의 원문이 아니다.** 그래서 화면에 그대로
     * 낸다 — `error-handling.md` 가 막는 것은 우리 내부 구조가 새는 것이고, 이 값에는 그런 것이
     * 없다. 다만 길이는 자른다. 상대가 보낸 값이 화면 계약의 길이를 정하게 두지 않는다.
     */
    private fun sdkErrorOf(payload: JsonNode): String =
        payload.path(ERROR_FIELD).asText(null)?.takeIf { it.isNotBlank() }?.take(ERROR_WIDTH)
            ?: GENERIC_SCAN_FAILURE

    private companion object {
        const val ACTION_FIELD = "action"
        const val RESULTS_FIELD = "results"
        const val SUCCESS_FIELD = "success"
        const val ERROR_FIELD = "error"

        /** `ingest_error` 의 컬럼 폭과 같은 값으로 맞춘다. 두 사유가 같은 칸에서 보이기 때문이다. */
        const val ERROR_WIDTH = 512

        const val GENERIC_SCAN_FAILURE = "게임이 근거 스캔에 실패했습니다."
        const val GENERIC_INGEST_FAILURE = "근거 문서를 씬 명세로 앉히지 못했습니다."
        const val NO_BUILD = "이 게임이 어느 빌드인지 아직 서버에 보고되지 않았습니다."
    }
}
