package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.qa.service.QaScreenFramePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** 답 하나를 적용한 결과. 타임라인에 남길 한 줄을 부르는 쪽이 이것으로 짓는다. */
data class ScreenNameOutcome(
    val screenId: Long? = null,
    /** 실제로 `screen.name` 에 앉은 이름. 답이 `null` 이었거나 거절됐으면 null 이다. */
    val name: String? = null,
    /** 거절 사유. 받아들였으면 null 이다. */
    val rejected: String? = null,
)

/**
 * `screen` 행이 새로 생기면 그 화면을 무엇이라 부를지 묻고, 답이 오면 `screen.name` 에 쓴다 (ARTEL-910).
 *
 * ```
 * pulse → screen 행이 새로 생겼다 (ScreenRepository.observe 의 inserted)
 *           ├→ capture 를 요청했다  → capture 가 붙은 뒤 → SCREEN_NAME_REQUEST
 *           └→ 요청하지 못했다      → 곧바로            → SCREEN_NAME_REQUEST
 *                                                            ↓
 *                                        screen.name ← SCREEN_NAME
 * ```
 *
 * ## 런을 세우지 않는다
 *
 * 묻고 끝이다. 답을 기다리는 자리가 없고, 답이 끝내 안 와도 `screen` 적재는 지금과 똑같이 돈다 —
 * 이름은 content map 의 전제가 아니라 표시값이기 때문이다(ARTEL-908). [ScreenSelectorProposalService]
 * 와 같은 판단이다.
 *
 * ## 한 번만 묻는다 — 장부는 `INSERT` 그 자체다
 *
 * `screen` 행은 `uk_screen_discriminator`(V59) 때문에 `(scene_id, discriminator)` 마다 평생 한 번만
 * `INSERT` 된다. 그 한 번이 `ScreenRepository.observe` 의 `inserted`(`xmax = 0`) 로 나오므로, 묻는
 * 계기를 거기 하나로 두면 표를 새로 만들지 않고도 "화면 하나에 질문 하나" 가 지켜진다.
 *
 * **`name IS NULL` 을 계기로 쓰지 않는 이유가 그것이다.** 모델이 `name: null` 로 답하는 것은 정상적인
 * 답인데(ARTEL-909), 비어 있는 칸을 계기로 삼으면 그 화면을 다시 볼 때마다 같은 질문이 나간다.
 * `screen_selector_proposal` 이 표 하나를 따로 둔 것은 그쪽의 계기가 `INSERT` 가 아니라 "목록 밖
 * selector 를 봤다" 라서 몇 번이고 다시 성립하기 때문이고, 여기는 그 사정이 없다.
 *
 * ## capture 를 기다리되 영영 기다리지는 않는다
 *
 * `screen capture` 가 붙은 뒤에 물어야 답이 훨씬 낫다 — 글만 보고 짓는 이름은 selector 이름을 다시
 * 쓰는 데 그친다. 그런데 `screen.image_object_key` 는 따로 오는 `ACTION_RESULT` 가 붙이고
 * ([ScreenCaptureResultRouter]), 그 왕복은 세 가지로 끝나지 않을 수 있다 — 붙은 SDK 가 없어 요청
 * 자체가 안 나갔거나, 게임이 `capture_screen` 을 몰라 실패로 답하거나, 아무 답도 안 온다.
 *
 * 그래서 기다리는 데 [CAPTURE_GRACE] 라는 마감을 둔다. 마감이 지난 것은 다음 `pulse` 에서
 * [askOverdue] 가 집어 capture 없이 묻는다. **캡처가 영영 안 붙는 화면도 결국 질문을 받는다**는
 * 것이 이 마감의 전부다.
 */
@Service
class ScreenNameService(
    private val screenRefs: ScreenRefs,
    private val screens: ScreenRepository,
    private val agent: QaScreenFramePort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScreenNameService::class.java)

    /**
     * 아직 안 물어본 화면들. 키가 `screen.id` 라 한 화면이 두 번 들어오지 않는다.
     *
     * `LinkedHashMap` 은 스스로 동기화하지 않으므로 감싼다. `pulse` 를 처리하는 쪽과
     * `ACTION_RESULT` 를 처리하는 쪽이 인스턴스가 여럿이면 서로 다른 스레드다.
     * [PendingScreenCaptureRegistry] 와 같은 모양이다.
     */
    private val unasked: MutableMap<Long, PendingScreenName> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, PendingScreenName>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, PendingScreenName>): Boolean = size > MAX_PENDING
        }
    )

    /**
     * 물어보고 답을 기다리는 것들. `request_id` 로 답을 화면에 되돌린다.
     *
     * DB 가 아니라 프로세스 메모리에 두는 것은 이 표의 수명이 왕복 하나이기 때문이다 — 답이 돌아올
     * agent socket 자체가 이 프로세스의 것이라, 재시작 뒤에 남아 있어 봐야 아무도 답하지 않는다.
     * [PendingScreenCaptureRegistry] 와 같은 판단이다.
     */
    private val awaitingAnswer: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean = size > MAX_PENDING
        }
    )

    /**
     * `screen` 행이 방금 생겼다. **새로 만든 행에서만 부른다.**
     *
     * @param captureRequested `capture_screen` 이 실제로 SDK 로 나갔나. 나갔으면 그림이 붙기를
     *   기다렸다 묻고, 안 나갔으면 기다릴 것이 없으니 지금 묻는다 — 기다려 봐야 오지 않을 답이다.
     */
    suspend fun onScreenInserted(
        gameInstanceId: Long,
        sceneId: Long,
        sceneName: String,
        screenId: Long,
        captureRequested: Boolean,
    ) {
        val pending = PendingScreenName(
            screenId = screenId,
            gameInstanceId = gameInstanceId,
            sceneId = sceneId,
            sceneName = sceneName,
            askAfter = clock.instant().plus(CAPTURE_GRACE),
        )
        unasked[screenId] = pending
        if (captureRequested) return
        ask(take(screenId) ?: return)
    }

    /**
     * 이 화면의 `screen capture` 왕복이 끝났다. 그림이 붙었든 실패했든 **더 기다릴 것이 없다.**
     *
     * 실패에도 부르는 것이 요점이다. 성공에만 부르면 `capture_screen` 을 모르는 빌드의 화면이
     * 마감까지 질문 없이 앉아 있게 되고, 그 마감은 그런 빌드에서 매번 걸린다.
     */
    suspend fun onCaptureSettled(screenId: Long) {
        ask(take(screenId) ?: return)
    }

    /**
     * 마감이 지난 것을 capture 없이 묻는다. `pulse` 마다 불린다.
     *
     * 기다리는 것이 없으면 맵 하나를 들여다보고 끝난다. 실측 런의 `pulse` 가 14489 개라 이 빠른
     * 갈래가 곧 이 함수의 비용 전부다.
     */
    suspend fun askOverdue() {
        if (unasked.isEmpty()) return
        val now = clock.instant()
        val overdue = synchronized(unasked) {
            unasked.values.filter { !it.askAfter.isAfter(now) }.also { due ->
                due.forEach { unasked.remove(it.screenId, it) }
            }
        }
        for (pending in overdue) ask(pending)
    }

    /**
     * 답 하나를 `screen.name` 에 쓴다 (`SCREEN_NAME`).
     *
     * 화면은 payload 가 아니라 **물어본 기록**에서 푼다. 답이 늦게 오면 그 사이 agent 는 다른
     * 화면에 서 있고, 그때 지금 서 있는 화면으로 풀면 남의 화면에 이름이 앉는다
     * ([ScreenSelectorProposalService.applyVerdict] 와 같은 규율이다).
     */
    suspend fun apply(correlationId: String?, payload: ScreenNamePayload): ScreenNameOutcome {
        val requestId = payload.requestId?.takeIf { it.isNotBlank() } ?: correlationId
        if (requestId.isNullOrBlank()) {
            return ScreenNameOutcome(rejected = "SCREEN_NAME needs the request id it answers")
        }
        val screenId = awaitingAnswer.remove(requestId)
            ?: return ScreenNameOutcome(rejected = "SCREEN_NAME references an unknown request: $requestId")

        val name = payload.name?.trim()
        if (name.isNullOrEmpty()) {
            // **정상적인 답이다.** 모델이 이름을 못 지었다는 뜻이고, 그때 칸은 비어 있는 채로 둔다 —
            // 스키마를 채우려고 지어낸 이름보다 빈 칸이 낫다(ARTEL-909).
            logger.info("화면 이름을 짓지 못했다는 답이 왔다 [screenId={}]: {}", screenId, payload.note ?: "사유 없음")
            return ScreenNameOutcome(screenId = screenId)
        }
        if (name.length > MAX_NAME_LENGTH) {
            val reason = "name is longer than $MAX_NAME_LENGTH characters"
            logger.warn("화면 이름이 너무 길어 쓰지 않았다 [screenId={}, length={}]", screenId, name.length)
            return ScreenNameOutcome(screenId = screenId, rejected = reason)
        }

        // **이미 이름이 있으면 덮지 않는다.** 판정을 코드가 아니라 `WHERE name IS NULL` 로 하는 것은
        // V60 · V67 의 화면 합치기가 남길 행에 다른 행의 이름을 이어 나르기 때문이다 — 먼저 읽고
        // 판단하면 그 사이에 앉은 이름을 늦게 온 답이 덮는다.
        val named = screens.nameIfAbsent(screenId, name)
        if (named == 0L) {
            logger.info("이미 이름이 있는 화면이라 쓰지 않는다 [screenId={}, name={}]", screenId, name)
            return ScreenNameOutcome(screenId = screenId)
        }
        logger.info("화면에 이름을 붙였다 [screenId={}, name={}]", screenId, name)
        return ScreenNameOutcome(screenId = screenId, name = name)
    }

    private fun take(screenId: Long): PendingScreenName? = unasked.remove(screenId)

    /**
     * 질문 하나를 보낸다. **실패를 삼킨다.**
     *
     * `pulse` 는 관측 채널이지 런의 전제가 아니고, 이름 짓기는 그 관측의 곁가지다 — 물어보다
     * 실패했다고 `pulse` 중계가 끊기면 화면을 못 만드는 게임에서 QA 자체가 눈을 잃는다
     * ([ScreenSelectorProposalService.propose] 와 같은 판단이다).
     */
    private suspend fun ask(pending: PendingScreenName) {
        try {
            send(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(
                "화면 이름을 물어보지 못했다 [gameInstanceId={}, screenId={}]: {}",
                pending.gameInstanceId, pending.screenId, failure.message, failure,
            )
        }
    }

    private suspend fun send(pending: PendingScreenName) {
        // 행을 다시 읽는 것이 두 가지를 겸한다 — 합쳐져 사라진 화면을 거르고(V67 의
        // `fold_scene_screens` 가 지운다), 그 사이에 이름이 앉았으면 묻지 않는다.
        val screen = screenRefs.of(pending.screenId) ?: return
        if (screen.name != null) return

        val requestId = UUID.randomUUID().toString()
        // 보내기 **전에** 넣는다. 뒤에 두면 답이 먼저 도착한 프레임을 우리 것으로 알아보지 못한다
        // (`ScreenCaptureService` 가 capture 를 보낼 때와 같은 순서다).
        awaitingAnswer[requestId] = pending.screenId
        val payload = ScreenNameRequestPayload(
            requestId = requestId,
            screen = screen,
            scene = ScreenSelectorSceneRef(pending.sceneId.toString(), pending.sceneName),
        )
        val delivered = agent.sendScreenName(
            gameInstanceId = pending.gameInstanceId,
            messageId = requestId,
            summary = summaryOf(pending.sceneName, screen),
            payload = objectMapper.valueToTree(payload),
        )
        if (delivered) return

        awaitingAnswer.remove(requestId)
        // 물어볼 상대가 없었다. 런 초반에 agent 세션이 아직 안 붙은(STARTING) 구간이 그 자리라,
        // 마감을 다시 걸어 다음 `pulse` 에서 한 번 더 시도한다. [MAX_ATTEMPTS] 로 끊는 것은 QA 런이
        // 아예 안 붙은 인스턴스에서 같은 화면을 계속 두드리지 않기 위해서다.
        if (pending.attempts + 1 >= MAX_ATTEMPTS) {
            logger.info("물어볼 상대가 없어 이름 없이 둔다 [screenId={}]", pending.screenId)
            return
        }
        unasked[pending.screenId] = pending.copy(
            askAfter = clock.instant().plus(RETRY_DELAY),
            attempts = pending.attempts + 1,
        )
    }

    /**
     * QA 타임라인의 한 줄. **그림이 붙었는지까지 적는다.**
     *
     * 이름이 나쁠 때 가장 먼저 의심할 것이 그것이라, 나중에 되짚는 사람이 프레임을 열지 않고도
     * 그 줄에서 바로 보여야 한다.
     */
    private fun summaryOf(sceneName: String, screen: ScreenSelectorScreenRef): String {
        val capture = if (screen.captureUrl == null) "no capture" else "capture attached"
        return "Asking what to call screen ${screen.screenId} of $sceneName ($capture)."
    }

    /**
     * 아직 안 물어본 화면 하나.
     *
     * @property askAfter 이 시각이 지나면 capture 를 더 기다리지 않고 묻는다.
     * @property attempts 보낼 곳이 없어 되돌아온 횟수.
     */
    private data class PendingScreenName(
        val screenId: Long,
        val gameInstanceId: Long,
        val sceneId: Long,
        val sceneName: String,
        val askAfter: Instant,
        val attempts: Int = 0,
    )

    companion object {
        /**
         * `screen.name` 에 쓸 이름의 길이 상한.
         *
         * 컬럼은 `VARCHAR(255)` 다(V40). 60 은 그보다 훨씬 좁은 **표시 규약**이고, agent-server 가
         * 같은 값으로 자기 답을 검사한다(ARTEL-909). 이름은 content map 화면의 한 칸과 QA agent 의
         * 매 턴 문장(`ScreenMap.render()`)에 들어가는데, 그 두 자리에서 60 자를 넘는 것은 이름이
         * 아니라 설명이다.
         */
        const val MAX_NAME_LENGTH = 60

        /**
         * `screen capture` 가 붙기를 기다려 주는 시간.
         *
         * 보통은 이 마감이 걸리지 않는다 — `ACTION_RESULT` 가 오면 [onCaptureSettled] 가 먼저 집어
         * 간다. 실측 캡처가 50KB 안팎이라 그 왕복은 초 단위로 끝난다
         * ([PendingScreenCaptureRegistry] 가 2 분을 고른 근거와 같다).
         *
         * 그래서 이 값은 "느린 왕복" 이 아니라 **"답이 영영 안 오는 경우"** 를 끊는 값이다. 캡처
         * 등록이 2 분까지 열려 있는 것과 달리 30 초로 두는 것은, 여기서 기다리는 대가가 그림 없는
         * 이름이 아니라 **이름 없는 화면**이기 때문이다 — 짧은 런은 2 분을 못 채우고 끝난다.
         */
        val CAPTURE_GRACE: Duration = Duration.ofSeconds(30)

        /** 보낼 곳이 없었을 때 다시 시도하기까지. 런 초반 STARTING 구간을 넘기면 되는 값이다. */
        val RETRY_DELAY: Duration = Duration.ofSeconds(10)

        /** 보낼 곳이 없는 화면 하나를 두드리는 횟수. */
        const val MAX_ATTEMPTS = 3

        /**
         * 동시에 기다릴 수 있는 화면 수. 한 씬의 화면 상한이 32 이므로
         * ([ScreenObservationService.MAX_SCREENS_PER_SCENE]) 여기 닿는다는 것은 한 서버가 수십 개의
         * 씬을 동시에 처음 만나고 있다는 뜻이다.
         */
        const val MAX_PENDING = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
