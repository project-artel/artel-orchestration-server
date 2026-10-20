package kr.artel.orchestration.project.config

import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.FilesystemDocumentStorage
import kr.artel.orchestration.project.storage.S3DocumentStorage
import kr.artel.orchestration.project.storage.StorageUrlSigner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Clock

/**
 * 저장소 구현을 `artel.storage.backend`(`ARTEL_STORAGE`)로 고른다.
 *
 * `@ConditionalOnProperty` 는 빈을 읽지 못해 키를 문자열로 쓴다. 기본값 `s3` 는 [StorageProperties.backend] 의
 * 기본값과 같이 움직여야 한다. `StorageObjectController` 와 `SecurityConfig.storageObjectSecurityWebFilterChain`
 * 도 같은 조건을 쓴다.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties::class)
class StorageConfig {

    /**
     * S3 클라이언트는 [S3DocumentStorage] 안에서 처음 쓸 때 만들어진다. 덕분에 테스트가
     * @Primary 가짜 저장소를 끼우면 AWS 객체가 아예 생성되지 않아, 자격증명 없는 환경에서도 돈다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "artel.storage", name = ["backend"], havingValue = "s3", matchIfMissing = true)
    fun documentStorage(properties: StorageProperties, clock: Clock): DocumentStorage =
        S3DocumentStorage(properties, clock)

    /**
     * 서명 키는 JWT 비밀에서 만든다. `ARTEL_SECRETS_KEY` 는 비워 둘 수 있지만 JWT 비밀은 기동에 필수라,
     * 이 모드에서 설정을 하나 더 요구하지 않는다. JWT 비밀을 바꾸면 이미 나간 URL 은 403 이 된다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "artel.storage", name = ["backend"], havingValue = "filesystem")
    fun storageUrlSigner(authProperties: AuthProperties, clock: Clock): StorageUrlSigner =
        StorageUrlSigner(authProperties.jwtSecret, clock)

    /**
     * URL 은 `ARTEL_HOME_URL` 을 앞에 붙인다. 브라우저는 그 주소의 `/api` 로 이 서버에 닿는다.
     * agent-server 에 내주는 URL 은 `ARTEL_INTERNAL_BASE_URL` 이 있으면 그것을 앞에 붙인다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "artel.storage", name = ["backend"], havingValue = "filesystem")
    fun filesystemDocumentStorage(
        properties: StorageProperties,
        authProperties: AuthProperties,
        signer: StorageUrlSigner,
        clock: Clock
    ): FilesystemDocumentStorage =
        FilesystemDocumentStorage(
            root = Path.of(requireNotNull(properties.dir)),
            publicBaseUrl = authProperties.frontendOrigin,
            internalBaseUrl = properties.internalBaseUrl,
            uploadUrlTtl = properties.uploadUrlTtl,
            downloadUrlTtl = properties.downloadUrlTtl,
            signer = signer,
            clock = clock
        )
}
