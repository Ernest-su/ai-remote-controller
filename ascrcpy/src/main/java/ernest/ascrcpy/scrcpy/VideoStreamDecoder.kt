package ernest.ascrcpy.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.os.Build
import android.view.Surface
import ernest.ascrcpy.adb.AdbChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive

internal class VideoStreamDecoder(
    private val channel: AdbChannel,
    private val surface: Surface,
    private val onVideoSize: (VideoSize) -> Unit,
    private val onRendered: (Long) -> Unit = {},
    private val onQueued: () -> Unit = {},
    private val onOutputFailure: (Throwable) -> Unit = {},
) {
    private var decoder: MediaCodec? = null
    private var size: VideoSize? = null
    private var outputThread: Thread? = null
    private val draining=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var outputFailure: Throwable? = null
    private var frames=0L

    suspend fun run() {
        val codecId = ByteBuffer.wrap(channel.readExactly(4)).order(ByteOrder.BIG_ENDIAN).int
        if (codecId != CODEC_H264) error("Unsupported scrcpy video codec: 0x${codecId.toString(16)}")
        try {
            while (currentCoroutineContext().isActive) {
                val header = channel.readExactly(12)
                if ((header[0].toInt() and 0x80) != 0) {
                    val data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    data.int
                    val newSize = VideoSize(data.int, data.int)
                    configure(newSize)
                    continue
                }
                val data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val ptsAndFlags = data.long
                val packetSize = data.int
                require(packetSize in 1..MAX_PACKET_SIZE) { "Invalid video packet size: $packetSize" }
                val packet = channel.readExactly(packetSize)
                queue(packet, ptsAndFlags)
                outputFailure?.let{throw IllegalStateException("Video output failed",it)}
            }
        } catch (error: Throwable) {
            if (error !is kotlinx.coroutines.CancellationException) {
                android.util.Log.e("AircVideoDecoder", "Video input failed: ${error.javaClass.simpleName}: ${error.message}")
                onOutputFailure(error)
            }
            throw error
        } finally {
            releaseDecoder()
        }
    }

    private fun configure(newSize: VideoSize) {
        require(newSize.width in 1..4096 && newSize.height in 1..4096 && newSize.width.toLong()*newSize.height<=4096L*2160){"Invalid video dimensions"}
        if (size == newSize && decoder != null) return
        releaseDecoder()
        size = newSize
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // Own the instance before configuring so a rejected format cannot leak a codec.
        decoder = codec
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, newSize.width, newSize.height)
            if (Build.VERSION.SDK_INT >= 30 && codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            android.util.Log.d("AircVideoDecoder", "Configuring ${codec.name}: ${newSize.width}x${newSize.height}")
            codec.configure(format, surface, null, 0)
            codec.start()
            android.util.Log.d("AircVideoDecoder", "Decoder started")
        } catch (error: Throwable) {
            releaseDecoder()
            throw error
        }
        // Output must keep draining while the input socket is idle on a static
        // screen. Waiting for the next packet can otherwise hide the first frame.
        onVideoSize(newSize)
        draining.set(true);outputFailure=null
        outputThread=Thread({
            val info=MediaCodec.BufferInfo()
            try{while(draining.get()){
                val index=codec.dequeueOutputBuffer(info,10_000)
                if(index>=0){codec.releaseOutputBuffer(index,true);onRendered(++frames)}
            }}catch(error:Throwable){if(draining.get()){outputFailure=error;onOutputFailure(error)}}
        },"airc-scrcpy-output").apply{start()}
    }

    private suspend fun queue(packet: ByteArray, ptsAndFlags: Long) {
        val codec = checkNotNull(decoder) { "Video packet received before session metadata" }
        var index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        val deadline=System.nanoTime()+5_000_000_000L
        while(index<0){
            currentCoroutineContext().ensureActive()
            outputFailure?.let{throw IllegalStateException("Video output failed",it)}
            check(System.nanoTime()<deadline){"Video decoder input timed out"}
            index=codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        }
        codec.getInputBuffer(index)!!.apply { clear();check(packet.size<=remaining()){"Video packet exceeds codec input buffer"};put(packet) }
        val config = ptsAndFlags and FLAG_CONFIG != 0L
        val pts = if (config) 0L else ptsAndFlags and PTS_MASK
        codec.queueInputBuffer(index, 0, packet.size, pts,
            if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        onQueued()
    }

    private fun releaseDecoder() {
        draining.set(false)
        outputThread?.join()
        outputThread=null
        decoder?.runCatching { stop() }
        decoder?.release()
        decoder = null
    }

    private companion object {
        const val CODEC_H264 = 0x68323634
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = FLAG_KEY_FRAME - 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_PACKET_SIZE = 16 * 1024 * 1024
    }
}

private suspend fun AdbChannel.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) error("Unexpected end of scrcpy stream")
        offset += count
    }
    return result
}
