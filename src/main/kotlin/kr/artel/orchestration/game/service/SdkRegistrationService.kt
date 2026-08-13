package kr.artel.orchestration.game.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.game.dto.SdkRegistrationRequest
import kr.artel.orchestration.game.dto.SdkRegistrationResponse
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.entity.GamePlatform
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

private const val BUILD_RETRY_ATTEMPTS = 3
private const val INSTANCE_RETRY_ATTEMPTS = 3

/**
 * SDK가 게임 실행마다 부르는 등록 지점.
 *
 * 요청자는 SDK 토큰으로 인증된 사용자다. 인가는 "그 사용자가 이 프로젝트의 참여자인가"로
 * 판단하고, 아니면 프로젝트가 없는 것과 같은 응답을 준다.
 *
 * 인스턴스는 대시보드에서 미리 만들지 않는다. (프로젝트, sdkUuid) 조합을 처음 보면 여기서
 * 만든다. 사람이 키를 발급받아 붙여넣는 단계를 없애기 위해서다.
 *
 * 한 번의 호출이 두 가지를 한다. 인스턴스에 "마지막으로 이 런타임이 이때 붙었다"를 기록하고,
 * 보고된 버전으로 게임 빌드를 찾거나 만든다. 버전 보고를 별도 호출로 나누지 않은 이유는,
 * 등록에 성공했지만 버전은 모르는 상태가 화면에서 설명할 수 없는 상태이기 때문이다.
 *
 * 요청에 씬 스캔이 실려 있으면 빌드에 함께 저장한다. 같은 빌드는 같은 씬 구성을 가지므로
 * 최신 스캔 하나만 남기고 재등록마다 덮어쓴다.
 */
@Service
class SdkRegistrationService(
    private val instanceRepository: GameInstanceRepository,
    private val buildRepository: GameBuildRepository,
    private val projectRepository: ProjectRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    /**
     * 프로젝트를 찾지 못하거나 요청자가 그 프로젝트의 참여자가 아니면 null이다. 컨트롤러가
     * 404로 옮긴다. 둘을 같은 응답으로 묶는 이유는, 구분해서 알려주면 프로젝트 id를 훑어
     * 남의 프로젝트가 존재한다는 사실을 알아낼 수 있기 때문이다.
     */
    suspend fun register(request: SdkRegistrationRequest, userId: Long): SdkRegistrationResponse? {
        val projectId = request.projectId.toLongOrNull() ?: return null
        projectRepository.findAccessibleById(projectId, userId) ?: return null

        val sdkUuid = request.sdkUuid.trim()
        val now = Instant.now(clock)
        val sceneScan = request.sceneScan?.let { Json.of(objectMapper.writeValueAsString(it)) }
        // 빌드 조회·생성과 인스턴스 갱신을 한 트랜잭션으로 묶는다.
        return transactionalOperator.executeAndAwait {
            val build = findOrCreateBuild(projectId, request.gameVersion.trim(), sceneScan, now)
            val instance = findOrCreateInstance(projectId, sdkUuid, request.instanceName, now)
            // 빌드 id를 인스턴스에 남긴다. 곧 열릴 웹소켓의 성능 런이 이 값을 읽어 자신을
            // 빌드에 묶는다(ARTEL-378 빌드 추세). 응답으로만 돌려주면 소켓 쪽에서 알 길이 없다.
            val saved = instanceRepository.save(
                instance.copy(
                    lastConnectedAt = now,
                    lastGameBuildId = build.id,
                    updatedAt = now
                )
            )
            SdkRegistrationResponse(
                instanceId = requireNotNull(saved.id).toString(),
                projectId = saved.projectId.toString(),
                instanceName = saved.name,
                gameBuildId = requireNotNull(build.id).toString(),
                gameVersion = build.version
            )
        }
    }

    /**
     * (프로젝트, sdkUuid)로 인스턴스를 찾고 없으면 만든다.
     *
     * 빌드와 같은 이유로 조회 후 저장 사이에 경합이 난다. 유니크 인덱스가 충돌을 예외로
     * 만들고, 여기서 다시 읽어 재시도한다.
     *
     * 이름은 만들 때만 쓴다. 매 등록마다 덮어쓰면 대시보드에서 고친 이름이 게임을 실행할
     * 때마다 되돌아간다.
     */
    private suspend fun findOrCreateInstance(
        projectId: Long,
        sdkUuid: String,
        requestedName: String?,
        now: Instant
    ): GameInstanceEntity {
        var remaining = INSTANCE_RETRY_ATTEMPTS
        while (true) {
            instanceRepository.findActiveBySdkUuid(projectId, sdkUuid)?.let { return it }
            try {
                return instanceRepository.save(
                    GameInstanceEntity(
                        projectId = projectId,
                        name = defaultName(requestedName, sdkUuid),
                        platform = GamePlatform.UNITY.name,
                        sdkUuid = sdkUuid,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            } catch (e: DataIntegrityViolationException) {
                if (remaining-- <= 0) throw e
            }
        }
    }

    /**
     * SDK가 이름을 보내지 않았을 때의 대체 이름. sdkUuid 앞자리를 붙여 목록에서 서로 구분은
     * 되게 하고, 사람이 알아볼 이름은 대시보드에서 바꾸게 둔다.
     */
    private fun defaultName(requestedName: String?, sdkUuid: String): String {
        val trimmed = requestedName?.trim()
        if (!trimmed.isNullOrEmpty()) {
            return trimmed.take(80)
        }
        return "SDK ${sdkUuid.take(8)}"
    }

    /**
     * 같은 (프로젝트, 버전)이면 기존 빌드를 그대로 쓴다. 스캔이 실려 있으면 기존 빌드라도
     * scene_scan을 새 값으로 덮어쓴다. 기존 행의 UPDATE는 유니크 제약과 충돌하지 않는다.
     *
     * 조회 후 저장 사이에 경합이 난다. 유니크 제약이 그 충돌을 예외로 만들고, 여기서 다시
     * 읽어 재시도한다. 재시도마다 조회를 새로 해야 이미 만들어진 행을 찾아 끝날 수 있다.
     *
     * 테스트가 H2에서 돌기 때문에 PostgreSQL의 ON CONFLICT는 쓸 수 없고, 양쪽에서 동작하는
     * 이 형태를 쓴다.
     */
    private suspend fun findOrCreateBuild(
        projectId: Long,
        version: String,
        sceneScan: Json?,
        now: Instant
    ): GameBuildEntity {
        var remaining = BUILD_RETRY_ATTEMPTS
        while (true) {
            try {
                val existing = buildRepository.findByProjectIdAndVersion(projectId, version)
                return when {
                    existing == null -> buildRepository.save(
                        GameBuildEntity(
                            projectId = projectId,
                            version = version,
                            sceneScan = sceneScan,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    sceneScan == null -> existing
                    else -> buildRepository.save(existing.copy(sceneScan = sceneScan, updatedAt = now))
                }
            } catch (e: DataIntegrityViolationException) {
                if (remaining-- <= 0) throw e
            }
        }
    }
}
