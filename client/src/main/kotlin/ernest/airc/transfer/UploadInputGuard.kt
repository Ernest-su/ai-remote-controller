package ernest.airc
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Closes a published source even when cancellation precedes completion of open(). */
internal class UploadInputGuard:AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val active=AtomicReference<InputStream?>(null)
    fun open(factory:()->InputStream):InputStream {
        if(closed.get())throw IOException("上传已取消")
        val stream=factory()
        if(!active.compareAndSet(null,stream)){runCatching{stream.close()};throw IOException("上传源已在使用")}
        if(closed.get()){
            active.compareAndSet(stream,null);runCatching{stream.close()};throw IOException("上传已取消")
        }
        return stream
    }
    fun release(stream:InputStream){if(active.compareAndSet(stream,null))stream.close()}
    override fun close(){closed.set(true);active.getAndSet(null)?.let{runCatching{it.close()}}}
}
