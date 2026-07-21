package kr.artel.orchestration.project.config

import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.S3DocumentStorage
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(StorageProperties::class)
class StorageConfig {

    /**
     * S3 클라이언트는 [S3DocumentStorage] 안에서 처음 쓸 때 만들어진다. 덕분에 테스트가
     * @Primary 가짜 저장소를 끼우면 AWS 객체가 아예 생성되지 않아, 자격증명 없는 환경에서도 돈다.
     */
    @Bean
    fun documentStorage(properties: StorageProperties, clock: Clock): DocumentStorage =
        S3DocumentStorage(properties, clock)
}
