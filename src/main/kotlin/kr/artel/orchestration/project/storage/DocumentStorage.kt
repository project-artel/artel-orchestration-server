package kr.artel.orchestration.project.storage

import reactor.core.publisher.Mono
import java.time.Instant

/**
 * 기획서 원본 저장소. 좁은 포트로 끊어두어 테스트에서 가짜 구현을 끼울 수 있고,
 * 저장소를 바꿔도 서비스 코드가 흔들리지 않는다.
 */
interface DocumentStorage {

    /** 업로드용 단기 URL을 만든다. 서명은 로컬 계산이라 네트워크 호출이 없다. */
    fun presignUpload(objectKey: String, contentType: String, contentLength: Long): PresignedUpload

    /** 다운로드용 단기 URL을 만든다. */
    fun presignDownload(objectKey: String, fileName: String): PresignedDownload

    /** 객체 메타데이터. 없으면 빈 Mono. */
    fun head(objectKey: String): Mono<StoredObject>

    /**
     * 객체의 앞부분을 읽는다. 형식 검증용이라 필요한 만큼만 가져온다.
     *
     * 저장된 Content-Type은 클라이언트가 신고한 값을 S3가 그대로 돌려주는 것이라
     * 내용에 대해 아무것도 보장하지 않는다. 실제 형식은 이 앞부분으로만 확인할 수 있다.
     */
    fun readPrefix(objectKey: String, length: Int): Mono<ByteArray>

    fun delete(objectKey: String): Mono<Void>
}

data class PresignedUpload(
    val url: String,
    val requiredHeaders: Map<String, String>,
    val expiresAt: Instant
)

data class PresignedDownload(
    val url: String,
    val expiresAt: Instant
)

data class StoredObject(
    val sizeBytes: Long,
    val contentType: String?
)
