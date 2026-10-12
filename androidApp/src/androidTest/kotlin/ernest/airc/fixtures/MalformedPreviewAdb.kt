package ernest.airc

import ernest.ascrcpy.adb.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory ADB facade feeding an unsupported or stalled codec header; never contacts a device. */
internal class MalformedPreviewAdb(private val stallAfterCodec:Boolean=false):AdbClient {
    override val state:StateFlow<AdbConnectionState> = MutableStateFlow(AdbConnectionState.Disconnected)
    val channels=CopyOnWriteArrayList<FixtureChannel>()
    val removals=AtomicInteger()
    @Volatile var closed=false
    @Volatile private var pushed:String?=null
    private val streams=AtomicInteger()
    override suspend fun connect(endpoint:AdbEndpoint):AdbDevice=error("Unexpected connect")
    override suspend fun disconnect(){close()}
    override suspend fun pull(remotePath:String,sink:OutputStream)=error("Unexpected pull")
    override suspend fun push(source:InputStream,remotePath:String,mode:Int){
        require(remotePath.matches(Regex("/data/local/tmp/airc-scrcpy-[a-f0-9-]+\\.jar")))
        var total=0;val block=ByteArray(4096)
        while(true){val count=source.read(block);if(count<0)break;total+=count;check(total<=8*1024*1024)}
        pushed=remotePath
    }
    override suspend fun shell(command:String):AdbCommandResult {
        check(command=="rm -f -- ${checkNotNull(pushed)}")
        removals.incrementAndGet()
        return AdbCommandResult(byteArrayOf(),0)
    }
    override suspend fun open(service:String):AdbChannel {
        val payload=if(service.startsWith("shell:CLASSPATH="))null else {
            check(service.startsWith("localabstract:scrcpy_"))
            val index=streams.getAndIncrement();check(index in 0..1)
            if(index==0){if(stallAfterCodec)byteArrayOf(0x68,0x32,0x36,0x34)else byteArrayOf(0,0,0,0)} else null
        }
        return FixtureChannel(payload).also{channels.add(it)}
    }
    override fun close(){closed=true;channels.forEach{it.close()}}
    class FixtureChannel(private val payload:ByteArray?):AdbChannel {
        private val ended=CompletableDeferred<Unit>()
        private var offset=0
        @Volatile var closed=false
        override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(payload!=null && this.offset<payload.size){val count=minOf(length,payload.size-this.offset)
                payload.copyInto(buffer,offset,this.offset,this.offset+count);this.offset+=count;return count}
            ended.await();return -1
        }
        override suspend fun write(buffer:ByteArray,offset:Int,length:Int)=error("Unexpected control write")
        override fun close(){closed=true;ended.complete(Unit)}
    }
}
