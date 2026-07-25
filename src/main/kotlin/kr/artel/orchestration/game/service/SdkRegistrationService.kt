package kr.artel.orchestration.game.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.game.dto.SdkRegistrationRequest
import kr.artel.orchestration.game.dto.SdkRegistrationResponse
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Clock
import java.time.Instant

private const val BUILD_RETRY_ATTEMPTS = 3L

/**
 * SDK가 게임 실행마다 부르는 등록 지점.
 *
 * 로그인한 사용자가 없다. instanceKey가 이 요청의 유일한 자격증명이고, 그 키로 인스턴스를
 * 찾지 못하면 그대로 끝난다.
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
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    /** 키로 인스턴스를 찾지 못하면 빈 Mono다. 컨트롤러가 404로 옮긴다. */
    @Transactional
    fun register(request: SdkRegistrationRequest): Mono<SdkRegistrationResponse> =
        instanceRepository.findActiveByInstanceKey(request.instanceKey.trim())
            .flatMap { instance ->
                val now = Instant.now(clock)
                val sceneScan = request.sceneScan?.let { Json.of(objectMapper.writeValueAsString(it)) }
                findOrCreateBuild(instance.projectId, request.gameVersion.trim(), sceneScan, now)
                    .flatMap { build ->
                        instanceRepository.save(
                            instance.copy(
                                lastSdkUuid = request.sdkUuid.trim(),
                                lastConnectedAt = now,
                                updatedAt = now
                            )
                        ).map { saved ->
                            SdkRegistrationResponse(
                                instanceId = requireNotNull(saved.id).toString(),
                                projectId = saved.projectId.toString(),
                                instanceName = saved.name,
                                gameBuildId = requireNotNull(build.id).toString(),
                                gameVersion = build.version
                            )
                        }
                    }
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
    private fun findOrCreateBuild(
        projectId: Long,
        version: String,
        sceneScan: Json?,
        now: Instant
    ): Mono<GameBuildEntity> =
        Mono.defer {
            buildRepository.findByProjectIdAndVersion(projectId, version)
                .flatMap { existing ->
                    if (sceneScan == null) {
                        Mono.just(existing)
                    } else {
                        buildRepository.save(existing.copy(sceneScan = sceneScan, updatedAt = now))
                    }
                }
                .switchIfEmpty(
                    Mono.defer {
                        buildRepository.save(
                            GameBuildEntity(
                                projectId = projectId,
                                version = version,
                                sceneScan = sceneScan,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                )
        }.retryWhen(
            Retry.max(BUILD_RETRY_ATTEMPTS)
                .filter { it is DataIntegrityViolationException }
        )
}
