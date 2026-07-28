package kr.artel.orchestration.project.storage

import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/**
 * 기획서 원본 저장소. 좁은 포트로 끊어두어 테스트에서 가짜 구현을 끼울 수 있고,
 * 저장소를 바꿔도 서비스 코드가 흔들리지 않는다.
 */
interface DocumentStorage {

    /** 업로드용 단기 URL을 만든다. 서명은 로컬 계산이라 네트워크 호출이 없다. */
    fun presignUpload(objectKey: String, contentType: String, contentLength: Long): PresignedUpload

    /**
     * 다운로드용 단기 URL을 만든다.
     *
     * [ttl]은 기본 유효 기간을 덮어쓴다. 사람이 즉시 클릭하는 링크와, 진행 중인 QA 런이
     * 끝날 때까지 살아 있어야 하는 캡처 링크는 필요한 수명이 다르다. 기본값을 캡처에 맞춰
     * 늘리면 기획서 다운로드 URL도 같이 길어지므로 호출 시점에 정한다.
     */
    fun presignDownload(
        objectKey: String,
        fileName: String,
        ttl: Duration? = null
    ): PresignedDownload

    /** 객체 메타데이터. 없으면 빈 Mono. */
    fun head(objectKey: String): Mono<StoredObject>

    /**
     * 객체의 앞부분을 읽는다. 형식 검증용이라 필요한 만큼만 가져온다.
     *
     * 저장된 Content-Type은 클라이언트가 신고한 값을 S3가 그대로 돌려주는 것이라
     * 내용에 대해 아무것도 보장하지 않는다. 실제 형식은 이 앞부분으로만 확인할 수 있다.
     */
    fun readPrefix(objectKey: String, length: Int): Mono<ByteArray>

    /**
     * 객체 전체의 SHA-256(소문자 hex)을 계산한다. 파일을 통째로 메모리에 올리지 않고 스트리밍하며
     * 다이제스트를 갱신하므로 파일 크기와 무관하게 상수 메모리다. 없는 객체면 빈 Mono.
     */
    fun sha256(objectKey: String): Mono<String>

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

/**
 * 저장소가 응답하지 못했다. 요청이 잘못된 것이 아니라 서버 쪽 설정이나 연결 문제이므로,
 * 클라이언트에게는 재시도할 수 있는 일시적 오류로 알린다.
 */
class DocumentStorageException(message: String, cause: Throwable) : RuntimeException(message, cause)
