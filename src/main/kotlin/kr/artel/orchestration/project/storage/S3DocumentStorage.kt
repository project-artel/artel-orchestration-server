package kr.artel.orchestration.project.storage

import kr.artel.orchestration.project.config.StorageProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * S3 어댑터.
 *
 * 서명(presign)은 로컬 HMAC 계산이라 네트워크 I/O가 없어 이벤트 루프에서 불러도 안전하다.
 * 반면 head/read/delete는 실제 호출이므로 반드시 비동기 클라이언트를 쓴다.
 *
 * 자격증명을 어디서도 찾지 못하면 서명 없이 동작한다([credentials]가 null). presigned URL은
 * 정의상 서명이므로 이때는 만들 수 없고, 대신 객체를 그대로 가리키는 URL을 돌려준다.
 * 버킷이 익명 접근을 허용할 때만 통하며, 서명이 없으니 만료도 없다.
 *
 * 클라이언트는 처음 쓸 때 만든다. 테스트가 가짜 저장소를 끼우면 이 구현은 주입되지 않으므로
 * AWS 객체가 아예 생성되지 않는다.
 */
class S3DocumentStorage internal constructor(
    private val properties: StorageProperties,
    private val clock: Clock,
    /** null이면 서명 없이 보낸다. */
    private val credentials: AwsCredentialsProvider?
) : DocumentStorage, DisposableBean {

    constructor(properties: StorageProperties, clock: Clock) :
        this(properties, clock, resolveCredentials(properties))

    private val region: Region get() = Region.of(properties.region)
    private val endpoint: URI? get() = properties.endpoint?.let(URI::create)

    private val lazyClient = lazy {
        S3AsyncClient.builder()
            .region(region)
            // 익명 자격증명은 요청에 서명을 붙이지 않는다.
            .credentialsProvider(credentials ?: AnonymousCredentialsProvider.create())
            .apply {
                endpoint?.let {
                    endpointOverride(it)
                    // MinIO 등 커스텀 엔드포인트는 버킷을 서브도메인으로 붙일 수 없다.
                    forcePathStyle(true)
                }
            }
            .build()
    }

    private val lazyPresigner = lazy {
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(
                requireNotNull(credentials) { "서명할 자격증명이 없으면 presigner를 쓰지 않는다" }
            )
            .apply {
                endpoint?.let {
                    endpointOverride(it)
                    // 서명 대상 URL 모양이 실제 PUT 대상과 같아야 서명이 맞는다.
                    serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build()
                    )
                }
            }
            .build()
    }

    private val client: S3AsyncClient get() = lazyClient.value
    private val presigner: S3Presigner get() = lazyPresigner.value

    /** 한 번도 쓰지 않았다면 만들지도 않았으므로 닫을 것도 없다. */
    override fun destroy() {
        if (lazyClient.isInitialized()) lazyClient.value.close()
        if (lazyPresigner.isInitialized()) lazyPresigner.value.close()
    }

    override fun presignUpload(
        objectKey: String,
        contentType: String,
        contentLength: Long
    ): PresignedUpload = wrapping {
        val url = if (credentials == null) {
            // 서명할 자격증명이 없다. 익명 쓰기를 허용하는 버킷에서만 통한다.
            objectUrl(objectKey)
        } else {
            // Content-Type을 서명에 포함시키면, 다른 타입을 신고한 PUT은 S3가 직접 거부한다.
            val putRequest = PutObjectRequest.builder()
                .bucket(properties.bucket)
                .key(objectKey)
                .contentType(contentType)
                .build()

            presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(properties.uploadUrlTtl)
                    .putObjectRequest(putRequest)
                    .build()
            ).url().toExternalForm()
        }

        PresignedUpload(
            url = url,
            requiredHeaders = mapOf("Content-Type" to contentType),
            expiresAt = Instant.now(clock).plus(properties.uploadUrlTtl)
        )
    }

    override fun presignDownload(
        objectKey: String,
        fileName: String,
        ttl: Duration?
    ): PresignedDownload = wrapping {
        val validity = ttl ?: properties.downloadUrlTtl
        val url = if (credentials == null) {
            // 서명이 없으면 response-content-disposition도 걸 수 없다. 브라우저는 원래
            // 파일 이름 대신 키의 마지막 조각으로 저장하게 된다.
            objectUrl(objectKey)
        } else {
            val getRequest = GetObjectRequest.builder()
                .bucket(properties.bucket)
                .key(objectKey)
                // 브라우저가 저장소 키가 아니라 원래 올린 이름으로 저장하도록 한다.
                .responseContentDisposition(contentDisposition(fileName))
                .build()

            presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(validity)
                    .getObjectRequest(getRequest)
                    .build()
            ).url().toExternalForm()
        }

        PresignedDownload(
            url = url,
            expiresAt = Instant.now(clock).plus(validity)
        )
    }

    override fun put(objectKey: String, content: ByteArray, contentType: String): Mono<Void> =
        Mono.fromFuture {
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(properties.bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .build(),
                AsyncRequestBody.fromBytes(content)
            )
        }
            .then()
            .onErrorMap(::isStorageFault, ::asStorageFault)

    override fun head(objectKey: String): Mono<StoredObject> =
        Mono.fromFuture {
            client.headObject(
                HeadObjectRequest.builder().bucket(properties.bucket).key(objectKey).build()
            )
        }
            .map { StoredObject(sizeBytes = it.contentLength(), contentType = it.contentType()) }
            .onErrorResume(::isMissing) { Mono.empty() }
            .onErrorMap(::isStorageFault, ::asStorageFault)

    override fun readPrefix(objectKey: String, length: Int): Mono<ByteArray> =
        Mono.fromFuture {
            client.getObject(
                GetObjectRequest.builder()
                    .bucket(properties.bucket)
                    .key(objectKey)
                    .range("bytes=0-${length - 1}")
                    .build(),
                AsyncResponseTransformer.toBytes()
            )
        }
            .map { it.asByteArray() }
            .onErrorResume(::isMissing) { Mono.empty() }
            .onErrorMap(::isStorageFault, ::asStorageFault)

    /**
     * 객체를 통째로 받지 않고 ByteBuffer 스트림으로 흘리며 SHA-256을 갱신한다. 파일 크기와 무관하게
     * 상수 메모리다(50MB 파일도 힙에 안 올림). [MessageDigest]는 이 한 스트림 안에서만 쓴다.
     */
    override fun sha256(objectKey: String): Mono<String> =
        Mono.fromFuture {
            client.getObject(
                GetObjectRequest.builder().bucket(properties.bucket).key(objectKey).build(),
                AsyncResponseTransformer.toPublisher()
            )
        }
            .flatMap { publisher ->
                val digest = MessageDigest.getInstance("SHA-256")
                Flux.from(publisher)
                    .doOnNext { buffer -> digest.update(buffer) }
                    .then(Mono.fromCallable { digest.digest().toHexLower() })
            }
            .onErrorResume(::isMissing) { Mono.empty() }
            .onErrorMap(::isStorageFault, ::asStorageFault)

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    override fun delete(objectKey: String): Mono<Void> =
        Mono.fromFuture {
            client.deleteObject(
                DeleteObjectRequest.builder().bucket(properties.bucket).key(objectKey).build()
            )
        }
            .then()
            .onErrorMap(::isStorageFault, ::asStorageFault)

    /** 없는 키는 실패가 아니라 "없음"이다. 비동기 클라이언트는 원인을 한 겹 감싸 던진다. */
    private fun isMissing(error: Throwable): Boolean =
        error is NoSuchKeyException || error.cause is NoSuchKeyException

    /**
     * 저장소 자체가 응답하지 못한 경우를 [DocumentStorageException]으로 바꾼다.
     *
     * 이렇게 하지 않으면 자격증명 누락 같은 설정 문제가 익명의 500으로 나와, 업로드 코드가
     * 잘못된 것처럼 보인다. 서버 설정이 덜 된 것과 요청이 잘못된 것은 구분되어야 한다.
     */
    private fun <T> wrapping(block: () -> T): T =
        try {
            block()
        } catch (error: SdkException) {
            throw asStorageFault(error)
        }

    private fun isStorageFault(error: Throwable): Boolean =
        error is SdkException || error.cause is SdkException

    private fun asStorageFault(error: Throwable) =
        DocumentStorageException("기획서 저장소에 접근하지 못했습니다.", error)

    /**
     * `Content-Disposition` 헤더 값. ASCII 폴백과 RFC 5987 형식을 **함께** 넣는다.
     *
     * `filename*`만 주면 그것을 이해하지 못하는 클라이언트가 파일 이름을 통째로 잃고 저장소 키의
     * 마지막 조각으로 저장한다. 반대로 비ASCII 이름을 따옴표 안에 그대로 넣으면 헤더가 깨진다.
     * 둘을 같이 주는 것이 RFC 6266이 정한 방식이고, 이해하는 쪽은 `filename*`을 우선한다.
     *
     * 인코딩은 여기서 **한 번만** 한다. 이 문자열은 presigned URL의 `response-content-disposition`
     * 쿼리 값으로 들어가며, 거기서 필요한 퍼센트 인코딩(`%` → `%25` 등)은 AWS SDK가 URL을
     * 조립하면서 붙인다. S3는 그것을 되돌려 이 헤더 값을 그대로 응답에 실으므로, 여기서 미리
     * 한 겹 더 인코딩하면 파일 이름에 `%25`가 그대로 보이게 된다.
     */
    private fun contentDisposition(fileName: String): String {
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
        return "attachment; filename=\"${asciiFallback(fileName)}\"; filename*=UTF-8''$encoded"
    }

    /**
     * 따옴표 안에 안전하게 들어갈 ASCII 이름. 비ASCII·제어문자·따옴표·역슬래시를 `_`로 바꾸고,
     * 이름 부분이 통째로 사라지면(예: 순한글 파일명) `download`로 대체하되 **확장자는 지킨다** —
     * 확장자를 잃으면 내려받은 파일을 운영체제가 무엇으로 열지 모른다.
     */
    private fun asciiFallback(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").filter { it.isSafeAscii() }
        val base = fileName.substringBeforeLast('.').map { if (it.isSafeAscii()) it else '_' }.joinToString("")
        val safeBase = if (base.any { it.isLetterOrDigit() }) base else "download"
        return if (extension.isBlank()) safeBase else "$safeBase.$extension"
    }

    /** 인용 문자열 안에서 헤더를 깨뜨리지 않는 출력 가능한 ASCII인지. */
    private fun Char.isSafeAscii(): Boolean =
        code in 0x20..0x7E && this != '"' && this != '\\'

    /**
     * 서명 없는 객체 URL. 커스텀 엔드포인트는 path-style(`{endpoint}/{bucket}/{key}`),
     * 아니면 가상 호스트 방식(`https://{bucket}.s3.{region}.amazonaws.com/{key}`)이다.
     */
    private fun objectUrl(objectKey: String): String {
        val encodedKey = objectKey.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20")
        }
        val base = properties.endpoint?.trimEnd('/')?.let { "$it/${properties.bucket}" }
            ?: "https://${properties.bucket}.s3.${properties.region}.amazonaws.com"
        return "$base/$encodedKey"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(S3DocumentStorage::class.java)

        /**
         * 설정 키 → AWS 기본 제공자 체인 → 없음 순으로 확인한다.
         *
         * 체인은 실제로 자격증명을 꺼내 봐야 있는지 알 수 있고, 없으면 예외를 던진다. 그것을
         * 여기서 한 번 확인해 두어, 나중에 업로드 요청 한복판에서 터지지 않게 한다.
         */
        internal fun resolveCredentials(properties: StorageProperties): AwsCredentialsProvider? {
            properties.staticCredentials?.let { (accessKey, secretKey) ->
                return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
                )
            }

            val chain = DefaultCredentialsProvider.create()
            return try {
                chain.resolveCredentials()
                chain
            } catch (error: SdkException) {
                logger.warn(
                    "AWS 자격증명을 찾지 못해 기획서 저장소에 서명 없이 접근한다. " +
                        "버킷이 익명 접근을 허용해야 하며, 발급되는 URL은 만료되지 않는다. " +
                        "원인: {}",
                    error.message
                )
                null
            }
        }
    }
}
