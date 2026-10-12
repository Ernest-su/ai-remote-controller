package ernest.airc.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Cuts only sockets owned by this disposable fixture, preserving its listening port. */
internal class TcpFaultRelay(private val upstreamPort:Int):AutoCloseable {
    private val lock=Any()
    private val listener=ServerSocket(0,16,InetAddress.getLoopbackAddress())
    private val workers=Executors.newCachedThreadPool{task->Thread(task,"airc-test-relay").apply{isDaemon=true}}
    private val pairs=mutableSetOf<Pair<Socket,Socket>>()
    private var allowed=true
    @Volatile private var closed=false
    val port:Int get()=listener.localPort
    init {workers.submit {
        while(!closed) {
            val incoming=try{listener.accept()}catch(error:Exception){if(closed)break else throw error}
            if(synchronized(lock){!allowed}){incoming.close();continue}
            val upstream=Socket()
            try {upstream.connect(java.net.InetSocketAddress("127.0.0.1",upstreamPort),3000)}
            catch(error:Exception){incoming.close();upstream.close();continue}
            val pair=incoming to upstream
            val accepted=synchronized(lock){if(!allowed||closed)false else {pairs.add(pair);true}}
            if(!accepted){incoming.close();upstream.close();continue}
            fun pump(source:Socket,destination:Socket) {workers.submit {
                try{source.getInputStream().copyTo(destination.getOutputStream(),64*1024)}
                catch(_:Exception){}finally{
                    synchronized(lock){pairs.remove(pair)}
                    runCatching{incoming.close()};runCatching{upstream.close()}
                }
            }}
            pump(incoming,upstream);pump(upstream,incoming)
        }
    }}
    fun cut() {
        val sockets=synchronized(lock){allowed=false;pairs.toList().also{pairs.clear()}}
        sockets.forEach{(a,b)->runCatching{a.close()};runCatching{b.close()}}
    }
    fun restore(){synchronized(lock){check(!closed);allowed=true}}
    override fun close(){closed=true;cut();listener.close();workers.shutdownNow()}
}
