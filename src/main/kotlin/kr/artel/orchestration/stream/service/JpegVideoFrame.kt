package kr.artel.orchestration.stream.service

import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** JPEG header로 메모리 상한을 먼저 확인한다. 전체 decode는 인코딩 thread에서만 한다. */
data class JpegVideoFrame(val content: ByteArray, val width: Int, val height: Int,
    val instant: Instant = Instant.now(), val nanoTime: Long = System.nanoTime()) {
    companion object {
        const val MAX_BYTES = 128 * 1024
        fun parse(base64: String): JpegVideoFrame {
            require(base64.length <= ((MAX_BYTES + 2) / 3) * 4)
            val instant = Instant.now()
            val nanos = System.nanoTime()
            val bytes = Base64.getDecoder().decode(base64)
            require(bytes.size in 4..MAX_BYTES && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
                && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                require(readers.hasNext())
                val reader = readers.next()
                try {
                    require(reader.formatName.equals("JPEG", true))
                    reader.input = input
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    require(width in 2..1920 && height in 2..1080 && width.toLong() * height <= 1920L * 1080)
                    return JpegVideoFrame(bytes, width, height, instant, nanos)
                } catch (error: java.io.IOException) {
                    throw IllegalArgumentException("JPEG header가 올바르지 않습니다.", error)
                } finally { reader.dispose() }
            }
        }
    }
}
