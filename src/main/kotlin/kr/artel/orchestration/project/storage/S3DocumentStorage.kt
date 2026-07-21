package kr.artel.orchestration.project.storage

import kr.artel.orchestration.project.config.StorageProperties
import org.springframework.beans.factory.DisposableBean
import reactor.core.publisher.Mono
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
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
import java.time.Clock
import java.time.Instant

/**
 * S3 어댑터.
 *
 * 서명(presign)은 로컬 HMAC 계산이라 네트워크 I/O가 없어 이벤트 루프에서 불러도 안전하다.
 * 반면 head/read/delete는 실제 호출이므로 반드시 비동기 클라이언트를 쓴다.
 *
 * 클라이언트는 처음 쓸 때 만든다. 테스트가 가짜 저장소를 끼우면 이 구현은 주입되지 않으므로,
 * 자격증명 없이 도는 환경에서도 AWS 객체가 아예 생성되지 않는다.
 */
class S3DocumentStorage(
    private val properties: StorageProperties,
    private val clock: Clock
) : DocumentStorage, DisposableBean {

    private val region: Region get() = Region.of(properties.region)
    private val endpoint: URI? get() = properties.endpoint?.let(URI::create)

    /**
     * 설정에 키가 있으면 그것을 쓰고, 없으면 AWS 기본 제공자 체인에 맡긴다.
     * 배포는 후자(인스턴스 역할), 로컬과 MinIO는 전자다.
     */
    private val credentialsProvider: AwsCredentialsProvider =
        properties.staticCredentials?.let { (accessKey, secretKey) ->
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        } ?: DefaultCredentialsProvider.create()

    private val lazyClient = lazy {
        S3AsyncClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
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
            .credentialsProvider(credentialsProvider)
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
        // Content-Type을 서명에 포함시키면, 다른 타입을 신고한 PUT은 S3가 직접 거부한다.
        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .contentType(contentType)
            .build()

        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(properties.uploadUrlTtl)
                .putObjectRequest(putRequest)
                .build()
        )

        PresignedUpload(
            url = presigned.url().toExternalForm(),
            requiredHeaders = mapOf("Content-Type" to contentType),
            expiresAt = Instant.now(clock).plus(properties.uploadUrlTtl)
        )
    }

    override fun presignDownload(objectKey: String, fileName: String): PresignedDownload = wrapping {
        val getRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            // 브라우저가 저장소 키가 아니라 원래 올린 이름으로 저장하도록 한다.
            .responseContentDisposition(contentDisposition(fileName))
            .build()

        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(properties.downloadUrlTtl)
                .getObjectRequest(getRequest)
                .build()
        )

        PresignedDownload(
            url = presigned.url().toExternalForm(),
            expiresAt = Instant.now(clock).plus(properties.downloadUrlTtl)
        )
    }

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

    private fun contentDisposition(fileName: String): String {
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
        return "attachment; filename*=UTF-8''$encoded"
    }
}
