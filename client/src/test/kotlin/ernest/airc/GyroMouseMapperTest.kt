package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class GyroMouseMapperTest {
    @Test fun ignoresFirstSampleDriftAndGapsAndResetsAfterPause() {
        val mapper=GyroMouseMapper()
        assertNull(mapper.sample(1_000_000_000,1f,1f,0,1f))
        val still = mapper.sample(1_020_000_000,0.01f,-0.01f,0,1f)!!
        assertEquals(0f, still.first, 0f); assertEquals(0f, still.second, 0f)
        assertNull(mapper.sample(2_000_000_000,1f,1f,0,1f))
        mapper.reset(); assertNull(mapper.sample(2_020_000_000,1f,1f,0,1f))
    }
    @Test fun mapsPitchYawAndDisplayRotationAndSensitivity() {
        fun sample(rotation:Int,gain:Float): Pair<Float,Float> {
            val mapper=GyroMouseMapper(); mapper.sample(1_000_000_000,1f,2f,rotation,gain)
            return mapper.sample(1_020_000_000,1f,2f,rotation,gain)!!
        }
        assertEquals(-144f to -72f,sample(0,1f))
        assertEquals(-72f to 144f,sample(1,1f))
        assertEquals(144f to 72f,sample(2,1f))
        assertEquals(72f to -144f,sample(3,1f))
        assertEquals(-288f to -144f,sample(0,2f))
    }
    @Test fun flatAndTiltedGripsUseGravityInsteadOfLosingHorizontalMotion() {
        fun sample(g: FloatArray,y:Float,z:Float): Pair<Float,Float> {
            val mapper=GyroMouseMapper(); mapper.sample(1_000_000_000,1f,y,0,1f,z,g)
            return mapper.sample(1_020_000_000,1f,y,0,1f,z,g)!!
        }
        val flat=sample(floatArrayOf(0f,0f,9.8f),0f,2f)
        assertEquals(-144f,flat.first,0.001f); assertEquals(-72f,flat.second,0.001f)
        val tilted=sample(floatArrayOf(0f,6.93f,6.93f),0.70710677f,0.70710677f)
        assertEquals(-72f,tilted.first,0.001f); assertEquals(-72f,tilted.second,0.001f)
    }
    @Test fun shortTurnsProduceUsefulTravelAndUpperSensitivityIsNotClampedAtTwo() {
        fun counts(gain:Float): Float {
            val mapper=GyroMouseMapper(); var total=0f
            mapper.sample(1_000_000_000,0f,1.3089969f,0,gain)
            repeat(10) { n -> total+=mapper.sample(1_020_000_000L+n*20_000_000L,0f,1.3089969f,0,gain)!!.first }
            return total
        }
        // A 15-degree turn now contributes about 1885 relative counts at sensitivity 2.
        assertEquals(-1884.9556f,counts(2f),0.1f)
        assertEquals(counts(2f)*3,counts(6f),0.1f)
    }
    @Test fun invalidSensorValueCannotMovePointer() {
        val mapper=GyroMouseMapper(); mapper.sample(1_000_000_000,0f,0f,0,1f)
        assertNull(mapper.sample(1_020_000_000,Float.NaN,1f,0,1f))
        assertNull(mapper.sample(1_010_000_000,1f,1f,0,1f))
    }
}
