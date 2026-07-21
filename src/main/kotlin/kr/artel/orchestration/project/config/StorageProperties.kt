package kr.artel.orchestration.project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 기획서 저장소 설정.
 *
 * 자격증명은 여기서 읽지 않는다. AWS 기본 제공자 체인(배포 시 인스턴스 역할, 로컬은 ~/.aws나
 * 환경변수)을 그대로 쓰므로 액세스 키가 설정 파일에 들어갈 자리가 없다.
 *
 * 허용 형식(application/pdf)은 설정이 아니라 상수다. 배포마다 다르게 두면 이후 파서가 읽지
 * 못하는 형식을 특정 환경만 받아들이는 상태가 생긴다.
 *
 * @property bucket 원본을 담을 버킷. 비어 있으면 기동을 멈춘다
 * @property endpoint 로컬 MinIO 등으로 돌릴 때만 지정한다
 * @property uploadUrlTtl 업로드 URL 유효 기간
 * @property downloadUrlTtl 다운로드 URL 유효 기간
 * @property maxUploadBytes 허용 최대 크기
 */
@ConfigurationProperties(prefix = "artel.storage")
data class StorageProperties(
    val bucket: String,
    val region: String = "ap-northeast-2",
    val endpoint: String? = null,
    val uploadUrlTtl: Duration = Duration.ofMinutes(10),
    val downloadUrlTtl: Duration = Duration.ofMinutes(5),
    val maxUploadBytes: Long = 52_428_800L
) {
    init {
        require(bucket.isNotBlank()) { "artel.storage.bucket must not be blank" }
        require(maxUploadBytes > 0) { "artel.storage.max-upload-bytes must be positive" }
        require(!uploadUrlTtl.isZero && !uploadUrlTtl.isNegative) {
            "artel.storage.upload-url-ttl must be positive"
        }
        require(!downloadUrlTtl.isZero && !downloadUrlTtl.isNegative) {
            "artel.storage.download-url-ttl must be positive"
        }
    }
}
