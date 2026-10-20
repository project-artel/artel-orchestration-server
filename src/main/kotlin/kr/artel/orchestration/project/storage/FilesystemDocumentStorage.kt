package kr.artel.orchestration.project.storage

import kr.artel.orchestration.common.error.BadRequestException
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 로컬 디렉터리에 객체를 두는 저장소. MinIO 없이 도는 데스크톱 번들이 쓴다(`ARTEL_STORAGE=filesystem`).
 *
 * S3 의 presigned URL 자리를 이 서버의 `/api/storage/objects/<objectKey>` 가 대신한다. URL 에는 만료 시각과
 * [StorageUrlSigner] 의 서명이 붙고, 그 경로는 세션 없이 서명만으로 열린다.
 *
 * [root] 아래 배치:
 * - `objects/<objectKey>` — 객체 본문
 * - `content-types/<objectKey>` — 업로드 때 신고된 Content-Type. 객체와 다른 트리에 두어 어떤 키도 이 파일과 겹치지 않는다
 * - `uploads-in-progress/` — 받는 중인 본문. 다 받은 뒤 `objects/` 로 원자적으로 옮겨, 읽는 쪽이 반쯤 쓴 파일을 보지 않는다
 *
 * 파일 I/O 는 막히는 호출이라 `boundedElastic` 에서 돈다. 본문은 스트리밍으로 쓰고 읽어, 파일 크기만큼 힙을 쓰지 않는다.
 * 예외는 [read] 로, 그 계약이 원래 객체를 통째로 돌려준다.
 */
class FilesystemDocumentStorage(
    root: Path,
    /** URL 의 앞부분. 브라우저와 agent-server 가 모두 닿는 주소여야 한다(`ARTEL_HOME_URL`). */
    publicBaseUrl: String,
    private val uploadUrlTtl: Duration,
    private val downloadUrlTtl: Duration,
    private val signer: StorageUrlSigner,
    private val clock: Clock
) : DocumentStorage {

    private val objectsRoot: Path
    private val contentTypesRoot: Path
    private val uploadsRoot: Path
    private val baseUrl = publicBaseUrl.trimEnd('/')

    init {
        val absoluteRoot = root.toAbsolutePath().normalize()
        Files.createDirectories(absoluteRoot)
        // 실제 경로로 고정해 둔다. 이후 모든 비교가 이 값과 하므로, 루트 자체가 심볼릭 링크여도 그 너머가 기준이 된다.
        val realRoot = absoluteRoot.toRealPath()
        objectsRoot = Files.createDirectories(realRoot.resolve("objects"))
        contentTypesRoot = Files.createDirectories(realRoot.resolve("content-types"))
        uploadsRoot = Files.createDirectories(realRoot.resolve("uploads-in-progress"))
    }

    override fun presignUpload(objectKey: String, contentType: String, contentLength: Long): PresignedUpload {
        requireValidObjectKey(objectKey)
        val expiresAt = Instant.now(clock).plus(uploadUrlTtl)
        val signature = signer.signUpload(objectKey, expiresAt, contentType, contentLength)
        return PresignedUpload(
            url = "${objectUrl(objectKey)}?expires=${expiresAt.epochSecond}&signature=$signature",
            requiredHeaders = mapOf("Content-Type" to contentType),
            expiresAt = expiresAt
        )
    }

    /**
     * 파일 이름은 `filename` 쿼리로 싣고 서명에 넣는다. S3 의 `response-content-disposition` 과 같은 방식이다.
     * 같은 객체를 부르는 쪽마다 이름이 달라(기획서 원래 이름, `screen-<id>.jpg`) 객체 쪽에 저장해 둘 수 없다.
     */
    override fun presignDownload(objectKey: String, fileName: String, ttl: Duration?): PresignedDownload {
        requireValidObjectKey(objectKey)
        val expiresAt = Instant.now(clock).plus(ttl ?: downloadUrlTtl)
        val signature = signer.signDownload(objectKey, expiresAt, fileName)
        return PresignedDownload(
            url = "${objectUrl(objectKey)}?expires=${expiresAt.epochSecond}" +
                "&filename=${encodeQueryValue(fileName)}&signature=$signature",
            expiresAt = expiresAt
        )
    }

    override fun put(objectKey: String, content: ByteArray, contentType: String): Mono<Void> =
        blocking {
            val target = writableObjectPath(objectKey)
            val temporary = newUploadPath()
            try {
                Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                commit(objectKey, temporary, target, contentType)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }.then()

    /**
     * 서명을 확인한 업로드 본문을 받는다. [contentLength] 를 넘는 순간 멈추고, 모자라게 끝나도 저장하지 않는다.
     *
     * 본문은 `uploads-in-progress/` 에 먼저 쓰고 다 받은 뒤에야 `objects/` 로 옮긴다. 도중에 끊기면 이전 객체가 남는다.
     */
    fun write(objectKey: String, contentType: String, contentLength: Long, body: Flux<DataBuffer>): Mono<Void> =
        blocking { writableObjectPath(objectKey) to newUploadPath() }
            .flatMap { (target, temporary) ->
                val receivedBytes = AtomicLong()
                val limitedBody = body.handle<DataBuffer> { buffer, sink ->
                    if (receivedBytes.addAndGet(buffer.readableByteCount().toLong()) > contentLength) {
                        DataBufferUtils.release(buffer)
                        sink.error(StorageObjectTooLargeException())
                    } else {
                        sink.next(buffer)
                    }
                }
                DataBufferUtils.write(limitedBody, temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    .then(blocking {
                        if (receivedBytes.get() != contentLength) throw StorageObjectIncompleteException()
                        commit(objectKey, temporary, target, contentType)
                    })
                    // 옮긴 뒤라면 지울 것이 없다. 실패한 업로드의 조각만 남지 않게 한다.
                    .doFinally { runCatching { Files.deleteIfExists(temporary) } }
            }
            .then()

    /** 내려받기 응답에 쓸 파일과 메타데이터. 없으면 빈 Mono. */
    fun find(objectKey: String): Mono<StoredFile> =
        blocking {
            val path = readableObjectPath(objectKey) ?: return@blocking null
            StoredFile(path = path, sizeBytes = Files.size(path), contentType = storedContentType(objectKey))
        }

    override fun head(objectKey: String): Mono<StoredObject> =
        find(objectKey).map { StoredObject(sizeBytes = it.sizeBytes, contentType = it.contentType) }

    override fun readPrefix(objectKey: String, length: Int): Mono<ByteArray> =
        blocking {
            val path = readableObjectPath(objectKey) ?: return@blocking null
            Files.newInputStream(path).use { it.readNBytes(length) }
        }

    override fun read(objectKey: String): Mono<ByteArray> =
        blocking { readableObjectPath(objectKey)?.let(Files::readAllBytes) }

    override fun sha256(objectKey: String): Mono<String> =
        blocking {
            val path = readableObjectPath(objectKey) ?: return@blocking null
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(path), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
            HexFormat.of().formatHex(digest.digest())
        }

    /** S3 처럼 없는 키를 지워도 성공이다. */
    override fun delete(objectKey: String): Mono<Void> =
        blocking {
            requireValidObjectKey(objectKey)
            Files.deleteIfExists(insideRoot(objectsRoot, objectKey))
            Files.deleteIfExists(insideRoot(contentTypesRoot, objectKey))
        }.then()

    /**
     * Content-Type 을 먼저 쓰고 본문을 옮긴다. 둘 다 임시 파일에서 원자적 이동이라, 읽는 쪽은 옛 파일이나 새 파일 중
     * 하나만 본다.
     */
    private fun commit(objectKey: String, temporary: Path, target: Path, contentType: String) {
        val contentTypePath = insideRoot(contentTypesRoot, objectKey)
        Files.createDirectories(contentTypePath.parent)
        requireRealPathInside(contentTypePath.parent, contentTypesRoot)
        val temporaryContentType = newUploadPath()
        try {
            Files.writeString(temporaryContentType, contentType, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            moveReplacing(temporaryContentType, contentTypePath)
        } finally {
            Files.deleteIfExists(temporaryContentType)
        }
        moveReplacing(temporary, target)
    }

    /** Windows 의 일부 파일시스템처럼 원자적 이동을 못 하는 곳에서는 일반 이동으로 물러난다. */
    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun storedContentType(objectKey: String): String? {
        val path = insideRoot(contentTypesRoot, objectKey)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.readString(path).trim().ifBlank { null }
    }

    private fun newUploadPath(): Path = uploadsRoot.resolve("${UUID.randomUUID()}.part")

    /** 쓸 자리. 부모 디렉터리를 만든 뒤, 그 실제 경로가 루트 안인지 다시 본다. 중간 디렉터리가 링크여도 밖으로 못 나간다. */
    private fun writableObjectPath(objectKey: String): Path {
        requireValidObjectKey(objectKey)
        val target = insideRoot(objectsRoot, objectKey)
        Files.createDirectories(target.parent)
        requireRealPathInside(target.parent, objectsRoot)
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw InvalidStorageObjectKeyException()
        return target
    }

    /** 읽을 자리. 없거나, 일반 파일이 아니거나, 링크를 따라가면 루트 밖이면 null 이다. */
    private fun readableObjectPath(objectKey: String): Path? {
        requireValidObjectKey(objectKey)
        val path = insideRoot(objectsRoot, objectKey)
        if (!Files.isRegularFile(path)) return null
        val realPath = path.toRealPath()
        if (!realPath.startsWith(objectsRoot)) {
            logger.warn("저장소 루트 밖을 가리키는 링크를 무시한다: key={}", objectKey)
            return null
        }
        return realPath
    }

    private fun insideRoot(base: Path, objectKey: String): Path {
        val resolved = base.resolve(objectKey).normalize()
        if (!resolved.startsWith(base) || resolved == base) throw InvalidStorageObjectKeyException()
        return resolved
    }

    private fun requireRealPathInside(directory: Path, base: Path) {
        if (!directory.toRealPath().startsWith(base)) throw InvalidStorageObjectKeyException()
    }

    private fun objectUrl(objectKey: String): String =
        "$baseUrl$OBJECTS_PATH/" + objectKey.split('/').joinToString("/", transform = ::encodeQueryValue)

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    /**
     * 파일 I/O 를 `boundedElastic` 으로 보낸다. 없는 파일은 빈 Mono 이고, 그 밖의 [IOException] 은 저장소 장애다.
     * 키 검증 실패 같은 [kr.artel.orchestration.common.error.ApiException] 은 그대로 둔다.
     */
    private fun <T : Any> blocking(block: () -> T?): Mono<T> =
        Mono.fromCallable<T>(block)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(NoSuchFileException::class.java) { Mono.empty() }
            .onErrorMap(IOException::class.java) { DocumentStorageException("파일 저장소에 접근하지 못했습니다.", it) }

    companion object {
        private val logger = LoggerFactory.getLogger(FilesystemDocumentStorage::class.java)

        /** 서명한 URL 이 가리키는 경로. `SecurityConfig` 의 storage 체인과 컨트롤러가 같은 값을 쓴다. */
        const val OBJECTS_PATH = "/api/storage/objects"

        private const val MAX_OBJECT_KEY_LENGTH = 1024

        /**
         * 객체 키가 [objectsRoot] 아래의 상대 경로로만 읽히는지 본다. 해석 결과를 루트와 비교하는 검사가 따로 있지만,
         * 운영체제마다 다르게 읽히는 문자(Windows 의 `\`, `:`)는 해석 전에 막아야 둘이 같은 결론을 낸다.
         */
        fun requireValidObjectKey(objectKey: String) {
            val isValid = objectKey.isNotEmpty() &&
                objectKey.length <= MAX_OBJECT_KEY_LENGTH &&
                !objectKey.startsWith('/') &&
                objectKey.none { it == '\\' || it == ':' || it.code < 0x20 || it.code == 0x7F } &&
                objectKey.split('/').none { it.isEmpty() || it == "." || it == ".." }
            if (!isValid) throw InvalidStorageObjectKeyException()
        }
    }
}

/** 내려받기 응답이 쓸 객체 파일. [path] 는 루트 안으로 확인한 실제 경로다. */
data class StoredFile(
    val path: Path,
    val sizeBytes: Long,
    val contentType: String?
)

/** 저장소 밖을 가리키거나 파일 이름으로 쓸 수 없는 키. */
class InvalidStorageObjectKeyException :
    BadRequestException("저장소 객체 키가 올바르지 않습니다.", code = "invalid_storage_object_key")

/** 업로드 URL 을 만들 때 신고한 크기보다 큰 본문. */
class StorageObjectTooLargeException :
    BadRequestException("업로드 본문이 신고한 크기를 넘습니다.", code = "storage_object_too_large")

/** 본문이 신고한 크기보다 먼저 끝났다. 잘린 파일을 객체로 남기지 않는다. */
class StorageObjectIncompleteException :
    BadRequestException("업로드 본문이 신고한 크기보다 작습니다.", code = "storage_object_incomplete")
