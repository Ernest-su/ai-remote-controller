package ernest.airc.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal fun silentWave(file:File,seconds:Int=20) {
    val size=44100*seconds*4
    val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(36+size);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(2)
        putInt(44100);putInt(176400);putShort(4);putShort(16);put("data".toByteArray());putInt(size)
    }.array()
    file.outputStream().use {output->output.write(header);val buffer=ByteArray(64*1024);var remaining=size;while(remaining>0){val n=minOf(remaining,buffer.size);output.write(buffer,0,n);remaining-=n}}
}
internal fun mediaDigest(file:File):String {
    val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
    return digest.digest().joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}
}
