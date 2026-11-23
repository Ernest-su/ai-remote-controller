package ernest.airc

import android.hardware.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hardware stream only; no network or remote input, direction/feel still requires manual aiming. */
class MouseGyroTargetTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun receivesRealGyroscopeAndGravitySamples() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkGyroscope") == "true")
        val manager=InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(SensorManager::class.java)
        val sensor=manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        assertNotNull("Target phone must have a gyroscope",sensor)
        val gravity=manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assertNotNull("Target phone must provide gravity or acceleration samples",gravity)
        var gyroSamples=0
        var gravitySamples=0
        val received=CompletableDeferred<Unit>()
        val listener=object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if(event.values.take(3).all { it.isFinite() }) {
                    if(event.sensor.type==Sensor.TYPE_GYROSCOPE) gyroSamples++ else gravitySamples++
                    if(gyroSamples>=10 && gravitySamples>=10) received.complete(Unit)
                }
            }
            override fun onAccuracyChanged(sensor:Sensor?, accuracy:Int) = Unit
        }
        try {
            assertTrue(manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_GAME))
            assertTrue(manager.registerListener(listener,gravity,SensorManager.SENSOR_DELAY_GAME))
            withTimeout(10_000) { received.await() }
        } finally { manager.unregisterListener(listener) }
    }
}
