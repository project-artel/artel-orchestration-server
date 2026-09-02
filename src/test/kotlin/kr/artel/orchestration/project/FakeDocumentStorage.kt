package kr.artel.orchestration.project

import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.DocumentStorageException
import kr.artel.orchestration.project.storage.PresignedDownload
import kr.artel.orchestration.project.storage.PresignedUpload
import kr.artel.orchestration.project.storage.StoredObject
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val FIXED_NOW: Instant = Instant.parse("2030-01-01T00:00:00Z")
private val DEFAULT_DOWNLOAD_TTL: Duration = Duration.ofMinutes(5)

/**
 * 실제 S3 대신 쓰는 인메모리 저장소.
 *
 * 업로드 경로 전체(티켓 발급 → 객체 확인 → 앞부분 검증)를 지나면서도 자격증명이나 목 서버가
 * 필요 없다. 테스트가 [put]으로 "클라이언트가 S3에 올린" 상태를 직접 만든다.
 */
class FakeDocumentStorage : DocumentStorage {

    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val contentTypes = ConcurrentHashMap<String, String>()

    /**
     * 켜면 [delete]가 [DocumentStorageException]으로 실패한다. 실 배포에서 IAM 정책에
     * `s3:DeleteObject`가 빠져 정리 delete가 403으로 실패했던 상황을 재현한다.
     */
    private var deleteFails = false

    /** 클라이언트가 presigned URL로 올린 것과 같은 상태를 만든다. */
    fun put(objectKey: String, content: ByteArray) {
        objects[objectKey] = content
    }

    /** 이후의 [delete] 호출을 전부 실패시킨다. 기본은 정상 동작(성공)이다. */
    fun failDeletes() {
        deleteFails = true
    }

    /** 서버가 [DocumentStorage.put]으로 올려 둔 바이트. 테스트가 결과를 확인할 때 쓴다. */
    /** 테스트가 저장된 바이트를 직접 들여다볼 때. 인터페이스의 [read] 와 달리 없는 키면 null 이다. */
    fun bytesOf(objectKey: String): ByteArray? = objects[objectKey]

    override fun read(objectKey: String): Mono<ByteArray> =
        objects[objectKey]?.let { Mono.just(it) } ?: Mono.empty()

    fun contentTypeOf(objectKey: String): String? = contentTypes[objectKey]

    fun clear() {
        deleteFails = false
        objects.clear()
        contentTypes.clear()
    }

    override fun presignUpload(
        objectKey: String,
        contentType: String,
        contentLength: Long
    ) = PresignedUpload(
        url = "https://fake-storage.test/$objectKey?signature=test",
        requiredHeaders = mapOf("Content-Type" to contentType),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z")
    )

    override fun presignDownload(
        objectKey: String,
        fileName: String,
        ttl: Duration?
    ) = PresignedDownload(
        url = "https://fake-storage.test/$objectKey?download=$fileName",
        // 만료 시각을 요청된 수명에서 그대로 계산한다. TTL이 런 데드라인을 넘는지 검증하는
        // 테스트가 이 값을 읽는다.
        expiresAt = FIXED_NOW.plus(ttl ?: DEFAULT_DOWNLOAD_TTL)
    )

    override fun put(objectKey: String, content: ByteArray, contentType: String): Mono<Void> =
        Mono.fromRunnable {
            objects[objectKey] = content
            contentTypes[objectKey] = contentType
        }

    override fun head(objectKey: String): Mono<StoredObject> =
        Mono.justOrEmpty(objects[objectKey])
            .map {
                StoredObject(
                    sizeBytes = it.size.toLong(),
                    contentType = contentTypes[objectKey] ?: "application/pdf"
                )
            }

    override fun readPrefix(objectKey: String, length: Int): Mono<ByteArray> =
        Mono.justOrEmpty(objects[objectKey]).map { it.copyOf(minOf(length, it.size)) }

    override fun sha256(objectKey: String): Mono<String> =
        Mono.justOrEmpty(objects[objectKey]).map { content ->
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        }

    override fun delete(objectKey: String): Mono<Void> {
        if (deleteFails) {
            return Mono.error(DocumentStorageException("기획서 저장소에 접근하지 못했습니다.", RuntimeException("403")))
        }
        objects.remove(objectKey)
        return Mono.empty()
    }
}
