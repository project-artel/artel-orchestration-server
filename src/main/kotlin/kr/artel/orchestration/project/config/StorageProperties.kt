package kr.artel.orchestration.project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 기획서 저장소 설정. 최소 설정은 [bucket]과 [region]뿐이다.
 *
 * 자격증명은 세 갈래로 정해진다.
 * 1. [accessKey]/[secretKey]가 있으면 그것을 쓴다. spring-dotenv는 `.env`를 Spring
 *    Environment에만 넣고 프로세스 환경변수로 만들지는 못해, `.env`에 AWS_ACCESS_KEY_ID를
 *    적어도 AWS SDK의 환경변수 제공자는 보지 못한다. 그래서 직접 넘길 길을 열어 둔다.
 * 2. 없으면 AWS 기본 제공자 체인(배포 시 인스턴스 역할, `~/.aws`, `AWS_*` 환경변수)을 쓴다.
 * 3. 그마저 없으면 서명 없이 보낸다. 버킷이 익명 접근을 허용할 때만 동작하며, 이때 발급되는
 *    업로드/다운로드 URL은 서명이 없으므로 만료되지도 않는다.
 *
 * 허용 형식(application/pdf)은 설정이 아니라 상수다. 배포마다 다르게 두면 이후 파서가 읽지
 * 못하는 형식을 특정 환경만 받아들이는 상태가 생긴다.
 *
 * @property bucket 원본을 담을 버킷. 비어 있으면 기동을 멈춘다
 * @property endpoint 로컬 MinIO 등으로 돌릴 때만 지정한다
 * @property accessKey 선택. [secretKey]와 함께 있어야 한다
 * @property secretKey 선택. [accessKey]와 함께 있어야 한다
 * @property uploadUrlTtl 업로드 URL 유효 기간
 * @property downloadUrlTtl 다운로드 URL 유효 기간
 * @property maxUploadBytes 허용 최대 크기
 */
@ConfigurationProperties(prefix = "artel.storage")
data class StorageProperties(
    val bucket: String,
    val region: String = "ap-northeast-2",
    val endpoint: String? = null,
    val accessKey: String? = null,
    val secretKey: String? = null,
    val uploadUrlTtl: Duration = Duration.ofMinutes(10),
    val downloadUrlTtl: Duration = Duration.ofMinutes(5),
    val maxUploadBytes: Long = 52_428_800L
) {
    /** 둘 다 있어야 쓴다. 하나만 있으면 설정 실수이므로 조용히 기본 체인으로 넘어가지 않는다. */
    val staticCredentials: Pair<String, String>? =
        if (!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()) {
            accessKey to secretKey
        } else {
            null
        }

    init {
        require(bucket.isNotBlank()) { "artel.storage.bucket must not be blank" }
        require(accessKey.isNullOrBlank() == secretKey.isNullOrBlank()) {
            "artel.storage.access-key and secret-key must be set together, or both left empty"
        }
        require(maxUploadBytes > 0) { "artel.storage.max-upload-bytes must be positive" }
        require(!uploadUrlTtl.isZero && !uploadUrlTtl.isNegative) {
            "artel.storage.upload-url-ttl must be positive"
        }
        require(!downloadUrlTtl.isZero && !downloadUrlTtl.isNegative) {
            "artel.storage.download-url-ttl must be positive"
        }
    }
}
