package ernest.airc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Single capture producer, single network consumer. No overwriting the beginning of an utterance. */
class BufferedVoicePcm(frames: Int = 400) {
    private val chunks = Channel<ByteArray>(frames)
    private var partial = ByteArray(640)
    private var size = 0
    val available = CompletableDeferred<Boolean>()
    var capturedBytes: Long = 0; private set
    fun append(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size && count % 2 == 0)
        var offset = 0
        while (offset < count) {
            val copied = minOf(640 - size, count - offset)
            bytes.copyInto(partial, size, offset, offset + copied)
            size += copied; offset += copied; capturedBytes += copied
            if (size == 640) { enqueue(partial); partial = ByteArray(640); size = 0 }
        }
    }
    private fun enqueue(bytes: ByteArray) {
        check(chunks.trySend(bytes).isSuccess) { "语音缓存已满，目标准备或网络传输过慢，请重试" }
        available.complete(true)
    }
    fun finish() {
        if (size > 0) { enqueue(partial.copyOf(size)); size = 0 }
        chunks.close(); available.complete(false)
    }
    suspend fun read(): ByteArray? = chunks.receiveCatching().let { result -> result.exceptionOrNull()?.let { throw it }; result.getOrNull() }
}
