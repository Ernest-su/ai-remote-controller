package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Explicit cleanup entry for a fixture interrupted before its JUnit rule could restore history. */
class DeviceHistoryRecoveryTest {
    @Test fun restoreInterruptedFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceHistoryIsolation.restore(context)
        val backup = context.getSharedPreferences("live-mirror-test-backup", 0)
        if (backup.getBoolean("active", false)) {
            val controls = context.getSharedPreferences("remote-controls", 0).edit()
            if (backup.getBoolean("had-transparency", false)) controls.putInt("floating-transparency", backup.getInt("transparency", 0))
            else controls.remove("floating-transparency")
            check(controls.commit())
            check(backup.edit().clear().commit())
        }
    }
}
