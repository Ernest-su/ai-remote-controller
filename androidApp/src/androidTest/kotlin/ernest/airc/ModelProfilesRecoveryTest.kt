package ernest.airc

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue

class ModelProfilesRecoveryTest {
    @Test fun restoreInterruptedEncryptedProfiles() {
        assumeTrue("Explicit recovery argument required",
            InstrumentationRegistry.getArguments().getString("restoreModelProfileBackup")=="true")
        assertTrue("没有待恢复的模型测试备份",
            EncryptedProfilesIsolation.restore(InstrumentationRegistry.getInstrumentation().targetContext))
    }
}
