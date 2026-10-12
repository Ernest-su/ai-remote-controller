package ernest.airc

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Versioned binary framing; no whole-stream buffer, redirects or inferred image dimensions. */
object ScreenPreviewReader {
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    fun validate(descriptor: ScreenPreviewDescriptor) {
        require(descriptor.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) && descriptor.path == "/preview/${descriptor.id}") { "无效预览路径" }
        require(descriptor.mimeType == "application/x-airc-preview" && descriptor.format == "airc-png-v1") { "不支持的预览格式" }
        require(descriptor.intervalMs in 500..5000 && descriptor.durationSeconds in 10..120 && descriptor.claimWithinSeconds == 30 && descriptor.maxFrameBytes == MAX_FRAME_BYTES) { "无效预览限制" }
    }
    fun read(input: InputStream, descriptor: ScreenPreviewDescriptor, onFrame: (ScreenPreviewFrame) -> Unit) {
        validate(descriptor)
        val source = DataInputStream(input)
        val magic = ByteArray(8); source.readFully(magic)
        check(magic.contentEquals("AIRCPRV1".toByteArray(Charsets.US_ASCII))) { "预览流版本不匹配" }
        var count = 0L; var elapsed = 0L
        while(true) {
            val sequence = source.readInt().toLong() and 0xffffffffL
            val width = source.readInt(); val height = source.readInt(); val size = source.readInt(); val clock = source.readLong()
            check(clock >= elapsed && clock <= descriptor.durationSeconds * 1000L + 30000L) { "预览时间戳无效" }
            if(width == 0 && height == 0) {
                check(sequence == count && size in 0..1024) { "预览结束记录无效" }
                if(size > 0) { val message = ByteArray(size); source.readFully(message); error(message.toString(Charsets.UTF_8)) }
                return
            }
            check(sequence == count + 1 && sequence <= 241 && width in 1..16384 && height in 1..16384 && width.toLong() * height <= 32L * 1024 * 1024 && size in 33..MAX_FRAME_BYTES) { "预览帧序号、尺寸或长度无效" }
            val png = ByteArray(size); source.readFully(png)
            val header = ByteBuffer.wrap(png)
            check(png.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) && header.getInt(8)==13 && header.getInt(12)==0x49484452 && header.getInt(16)==width && header.getInt(20)==height) { "预览 PNG 与帧尺寸不匹配" }
            onFrame(ScreenPreviewFrame(sequence, clock, width, height, png)); count = sequence; elapsed = clock
        }
    }
}
