package kr.artel.orchestration.project.storage

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 내려받기 응답의 `Content-Disposition` 헤더 값. ASCII 폴백과 RFC 5987 형식을 **함께** 넣는다.
 *
 * `filename*`만 주면 그것을 이해하지 못하는 클라이언트가 파일 이름을 통째로 잃고 저장소 키의
 * 마지막 조각으로 저장한다. 반대로 비ASCII 이름을 따옴표 안에 그대로 넣으면 헤더가 깨진다.
 * 둘을 같이 주는 것이 RFC 6266이 정한 방식이고, 이해하는 쪽은 `filename*`을 우선한다.
 *
 * S3 는 이 값을 presigned URL 의 쿼리로 받아 응답에 싣고, 파일시스템 저장소는 응답 헤더에 직접 싣는다.
 * 두 구현이 같은 값을 내도록 한 곳에 둔다.
 */
internal fun attachmentContentDisposition(fileName: String): String {
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
