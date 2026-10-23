package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Hidden
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.storage.FilesystemDocumentStorage
import kr.artel.orchestration.project.storage.InvalidStorageObjectKeyException
import kr.artel.orchestration.project.storage.StorageSignatureException
import kr.artel.orchestration.project.storage.StorageUrlSigner
import kr.artel.orchestration.project.storage.attachmentContentDisposition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/**
 * 파일시스템 저장소의 서명 URL 을 받는 곳. S3 모드에서는 presigned URL 을 S3 가 직접 받으므로 이 컨트롤러가 없다.
 *
 * 세션 없이 열린다(`SecurityConfig.storageObjectSecurityWebFilterChain`). 자격증명은 URL 의 서명 하나뿐이고,
 * 업로드는 SDK·CLI·브라우저가, 내려받기는 브라우저와 agent-server 가 부른다. 서명이 틀리거나 만료되면 403 이다.
 */
@Hidden
@RestController
@RequestMapping(FilesystemDocumentStorage.OBJECTS_PATH)
// 키와 값은 StorageConfig 의 filesystem 빈 조건과 같다. 한쪽만 바꾸면 컨트롤러가 빈 없이 뜨거나 빈이 경로 없이 뜬다.
@ConditionalOnProperty(prefix = "artel.storage", name = ["backend"], havingValue = "filesystem")
class StorageObjectController(
    private val storage: FilesystemDocumentStorage,
    private val signer: StorageUrlSigner
) {

    /**
     * 본문을 받아 저장한다. 서명은 요청의 Content-Type 과 Content-Length 헤더로 확인한다 — presign 때와 다른 형식이나
     * 크기를 보내면 서명이 맞지 않는다. 그래서 Content-Length 가 없는(chunked) 요청은 받지 않는다. S3 도 같다.
     */
    @PutMapping("/**")
    fun upload(request: ServerHttpRequest): Mono<ResponseEntity<Void>> {
        val objectKey = objectKeyOf(request)
        val contentType = request.headers.getFirst(HttpHeaders.CONTENT_TYPE)?.trim()
            ?: throw StorageSignatureException()
        val contentLength = request.headers.contentLength.takeIf { it >= 0 }
            ?: throw BadRequestException("Content-Length 헤더가 필요합니다.", code = "content_length_required")
        signer.verifyUpload(
            objectKey = objectKey,
            expiresEpochSeconds = expiresOf(request),
            contentType = contentType,
            contentLength = contentLength,
            signature = signatureOf(request)
        )
        return storage.write(objectKey, contentType, contentLength, request.body)
            .thenReturn(ResponseEntity.ok().build())
    }

    /**
     * 객체를 내려준다. `GET` 매핑이 `HEAD` 도 받는다.
     *
     * 이 응답은 home 과 같은 origin 에서 나간다. 저장된 Content-Type 은 업로드한 쪽이 신고한 값이라, `attachment` 와
     * `sandbox` CSP 로 브라우저가 그것을 이 origin 의 문서로 열지 않게 한다. `<img>` 로 싣는 것은 막지 않는다.
     */
    @GetMapping("/**")
    fun download(request: ServerHttpRequest): Mono<ResponseEntity<Resource>> {
        val objectKey = objectKeyOf(request)
        val fileName = request.queryParams.getFirst("filename") ?: throw StorageSignatureException()
        signer.verifyDownload(
            objectKey = objectKey,
            expiresEpochSeconds = expiresOf(request),
            fileName = fileName,
            signature = signatureOf(request)
        )
        return storage.find(objectKey)
            .switchIfEmpty(Mono.error { NotFoundException("저장된 객체를 찾을 수 없습니다.") })
            .map { stored ->
                ResponseEntity.ok()
                    .contentType(stored.contentType?.let(::parseMediaTypeOrNull) ?: MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(stored.sizeBytes)
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachmentContentDisposition(fileName))
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(FileSystemResource(stored.path))
            }
    }

    /**
     * 경로에서 객체 키를 꺼낸다. 디코딩은 조각마다 한 번만 하고, 디코딩해서 `/` 가 생긴 조각(`%2F`)은 거절한다 —
     * 그런 조각을 받으면 서명한 키와 다른 디렉터리 구조로 읽힌다.
     */
    private fun objectKeyOf(request: ServerHttpRequest): String {
        val rawPath = request.path.pathWithinApplication().value()
        val rawKey = rawPath.removePrefix("${FilesystemDocumentStorage.OBJECTS_PATH}/")
        if (rawKey == rawPath) throw InvalidStorageObjectKeyException()
        val segments = rawKey.split('/').map { UriUtils.decode(it, StandardCharsets.UTF_8) }
        if (segments.any { it.contains('/') }) throw InvalidStorageObjectKeyException()
        return segments.joinToString("/").also(FilesystemDocumentStorage::requireValidObjectKey)
    }

    private fun expiresOf(request: ServerHttpRequest): Long =
        request.queryParams.getFirst("expires")?.toLongOrNull() ?: throw StorageSignatureException()

    private fun signatureOf(request: ServerHttpRequest): String =
        request.queryParams.getFirst("signature") ?: throw StorageSignatureException()

    private fun parseMediaTypeOrNull(value: String): MediaType? =
        runCatching { MediaType.parseMediaType(value) }.getOrNull()
}
