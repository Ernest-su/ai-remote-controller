package ernest.airc

import kotlin.math.abs
import kotlin.math.sqrt

/** Gravity-projected yaw works with upright, tilted and flat grips; no magnetic compass required. */
class GyroMouseMapper {
    private var previous = 0L
    fun reset() { previous = 0L }
    fun sample(timestamp: Long, x: Float, y: Float, rotation: Int, sensitivity: Float,
        z: Float = 0f, gravity: FloatArray? = null): Pair<Float,Float>? {
        val last = previous; previous = timestamp
        if (last == 0L || timestamp <= last || timestamp-last > 100_000_000L ||
            !x.isFinite() || !y.isFinite() || !z.isFinite() || !sensitivity.isFinite()) return null
        val right = when (rotation) { 1 -> floatArrayOf(0f,-1f,0f); 2 -> floatArrayOf(-1f,0f,0f); 3 -> floatArrayOf(0f,1f,0f); else -> floatArrayOf(1f,0f,0f) }
        val fallback = when (rotation) { 1 -> floatArrayOf(1f,0f,0f); 2 -> floatArrayOf(0f,-1f,0f); 3 -> floatArrayOf(-1f,0f,0f); else -> floatArrayOf(0f,1f,0f) }
        val g = gravity?.takeIf { it.size >= 3 && it.take(3).all(Float::isFinite) } ?: fallback
        val length = sqrt(g[0]*g[0]+g[1]*g[1]+g[2]*g[2])
        if (length < 0.01f) return null
        val gx=g[0]/length; val gy=g[1]/length; val gz=g[2]/length
        val dot=right[0]*gx+right[1]*gy+right[2]*gz
        var rx=right[0]-dot*gx; var ry=right[1]-dot*gy; var rz=right[2]-dot*gz
        val horizontal=sqrt(rx*rx+ry*ry+rz*rz)
        if (horizontal>0.01f) { rx/=horizontal; ry/=horizontal; rz/=horizontal }
        else { rx=right[0]; ry=right[1]; rz=right[2] }
        val pitch=x*rx+y*ry+z*rz
        val yaw=x*gx+y*gy+z*gz
        fun filtered(value: Float) = if (abs(value) <= 0.015f) 0f else value.coerceIn(-8f,8f)
        val scale=(timestamp-last)/1_000_000_000f*3600f*sensitivity.coerceIn(0.5f,6f)
        return -filtered(yaw)*scale to -filtered(pitch)*scale
    }
}
