package kr.artel.orchestration.tracker.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import kr.artel.orchestration.tracker.github.GitHubUserInstallation
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component

/** 확인된 installation 목록의 Redis key 접두사. 테스트가 이 접두사로 키를 지운다. */
internal const val INSTALLATION_CHOICE_KEY_PREFIX = "artel:tracker:github:installations:"

/**
 * 사용자 인가 복귀에서 서버가 GitHub 에서 직접 읽은 installation 목록. `PUT /installation` 이 이 목록과
 * 대조하므로, 클라이언트가 보낸 `installationId` 를 믿지 않아도 된다.
 *
 * 키가 `(projectId, userId)` 라 같은 프로젝트의 다른 OWNER 도 남이 읽어 둔 목록으로 연결하지 못한다.
 * 값에는 installation id, 계정 이름, 계정 종류만 둔다. user token 은 저장하지 않는다.
 *
 * 만료는 키 TTL([GitHubAppProperties.installationChoiceTtl])이 판정한다.
 *
 * 값이 필드 셋의 목록이라 `SdkLoginCodeStore` 처럼 구분자로 잇지 않고 JSON 으로 둔다.
 *
 * Redis 장애는 예외로 그대로 올린다. null 로 삼키면 호출부가 409 "다시 인가하라"를 내어, 장애가
 * 사용자 쪽 만료로 보이게 된다(`SdkLoginCodeStore.consume` 과 같은 판단).
 */
@Component
class TrackerInstallationChoiceStore(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: GitHubAppProperties
) {
    private val logger = LoggerFactory.getLogger(TrackerInstallationChoiceStore::class.java)

    /** 같은 키는 덮어쓴다. 다시 인가하면 그때 읽은 목록이 이전 목록을 대신한다. */
    suspend fun save(projectId: Long, userId: Long, installations: List<GitHubUserInstallation>) {
        redis.opsForValue()
            .set(
                keyOf(projectId, userId),
                objectMapper.writeValueAsString(installations),
                properties.installationChoiceTtl
            )
            .awaitSingle()
    }

    /** 없거나 만료됐으면 null. 빈 목록은 null 이 아니다 — App 이 아직 어디에도 설치되지 않은 사람이다. */
    suspend fun read(projectId: Long, userId: Long): List<GitHubUserInstallation>? {
        val stored = redis.opsForValue().get(keyOf(projectId, userId)).awaitSingleOrNull()
            ?: return null
        // `List::class.java` 로 읽으면 원소가 `GitHubUserInstallation` 이 아니라 map 이 된다.
        return objectMapper.readValue(stored, jacksonTypeRef<List<GitHubUserInstallation>>())
    }

    /**
     * 연결이 끝난 뒤 목록을 지운다.
     *
     * `read` 와 따로 부르므로 동시에 온 `PUT` 두 개가 모두 통과할 수 있다. 둘 다 같은 사람의 확인된 목록
     * 안의 id 이고 `attachInstallation` 은 upsert 라 결과가 망가지지 않는다. `GETDEL` 로 묶지 않는 이유는
     * 목록에 없는 id 로 온 잘못된 `PUT` 하나가 목록을 지워 버리면 안 되기 때문이다.
     *
     * 이 메서드만 Redis 장애를 삼킨다. 부르는 시점에는 연결이 이미 저장되어 있어, 여기서 예외가 나가면
     * 성공한 `PUT` 이 500 으로 보인다. 지우지 못한 키는 TTL 이 지운다.
     */
    suspend fun delete(projectId: Long, userId: Long) {
        try {
            redis.delete(keyOf(projectId, userId)).awaitSingle()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("installation 목록 삭제 실패: project={}, user={}", projectId, userId, error)
        }
    }

    private fun keyOf(projectId: Long, userId: Long): String =
        "$INSTALLATION_CHOICE_KEY_PREFIX$projectId:$userId"
}
