package kr.artel.orchestration.project

import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.PresignedDownload
import kr.artel.orchestration.project.storage.PresignedUpload
import kr.artel.orchestration.project.storage.StoredObject
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 실제 S3 대신 쓰는 인메모리 저장소.
 *
 * 업로드 경로 전체(티켓 발급 → 객체 확인 → 앞부분 검증)를 지나면서도 자격증명이나 목 서버가
 * 필요 없다. 테스트가 [put]으로 "클라이언트가 S3에 올린" 상태를 직접 만든다.
 */
class FakeDocumentStorage : DocumentStorage {

    private val objects = ConcurrentHashMap<String, ByteArray>()

    /** 클라이언트가 presigned URL로 올린 것과 같은 상태를 만든다. */
    fun put(objectKey: String, content: ByteArray) {
        objects[objectKey] = content
    }

    fun clear() = objects.clear()

    override fun presignUpload(
        objectKey: String,
        contentType: String,
        contentLength: Long
    ) = PresignedUpload(
        url = "https://fake-storage.test/$objectKey?signature=test",
        requiredHeaders = mapOf("Content-Type" to contentType),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z")
    )

    override fun presignDownload(objectKey: String, fileName: String) = PresignedDownload(
        url = "https://fake-storage.test/$objectKey?download=$fileName",
        expiresAt = Instant.parse("2030-01-01T00:00:00Z")
    )

    override fun head(objectKey: String): Mono<StoredObject> =
        Mono.justOrEmpty(objects[objectKey])
            .map { StoredObject(sizeBytes = it.size.toLong(), contentType = "application/pdf") }

    override fun readPrefix(objectKey: String, length: Int): Mono<ByteArray> =
        Mono.justOrEmpty(objects[objectKey]).map { it.copyOf(minOf(length, it.size)) }

    override fun sha256(objectKey: String): Mono<String> =
        Mono.justOrEmpty(objects[objectKey]).map { content ->
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        }

    override fun delete(objectKey: String): Mono<Void> {
        objects.remove(objectKey)
        return Mono.empty()
    }
}
