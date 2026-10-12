package ernest.airc

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Private test preference namespaces; never replaces the user's model configuration. */
class EncryptedProfilesIsolationTest {
    private fun isolated(test:(Context)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="profile-isolation-${UUID.randomUUID()}-"
        val context=object:ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences =
                base.getSharedPreferences(prefix+name,mode)
        }
        try { test(context) }
        finally { listOf("model-profiles","model-profiles-test-backup").forEach { base.deleteSharedPreferences(prefix+it) } }
    }

    @Test fun interruptedTestRestoresOriginalCiphertext()=isolated { context->
        val original=context.getSharedPreferences("model-profiles",0)
        assertTrue(original.edit().putString("encrypted-v1","original-ciphertext").commit())
        EncryptedProfilesIsolation().isolate(context)
        assertFalse(original.contains("encrypted-v1"))
        assertTrue(original.edit().putString("encrypted-v1","test-ciphertext").commit())
        // A new helper represents recovery after the old test process was interrupted.
        assertTrue(EncryptedProfilesIsolation.restore(context))
        assertEquals("original-ciphertext",original.getString("encrypted-v1",null))
        assertTrue(context.getSharedPreferences("model-profiles-test-backup",0).all.isEmpty())
        assertFalse(EncryptedProfilesIsolation.restore(context))
    }

    @Test fun unfinishedBackupRejectsAnotherIsolationWithoutChangingIt()=isolated { context->
        val original=context.getSharedPreferences("model-profiles",0)
        assertTrue(original.edit().putString("encrypted-v1","original-ciphertext").commit())
        EncryptedProfilesIsolation().isolate(context)
        assertTrue(original.edit().putString("encrypted-v1","test-ciphertext").commit())
        val backup=context.getSharedPreferences("model-profiles-test-backup",0)
        val before=backup.all.toMap()
        var rejected=false
        try { EncryptedProfilesIsolation().isolate(context) } catch(_:IllegalStateException) { rejected=true }
        assertTrue(rejected);assertEquals(before,backup.all)
        assertEquals("test-ciphertext",original.getString("encrypted-v1",null))
        assertTrue(EncryptedProfilesIsolation.restore(context))
        assertEquals("original-ciphertext",original.getString("encrypted-v1",null))
    }

    @Test fun emptyOriginalRemovesOnlyTheTestCiphertextOnRecovery()=isolated { context->
        val original=context.getSharedPreferences("model-profiles",0)
        assertTrue(original.edit().putString("other","preserved").commit())
        EncryptedProfilesIsolation().isolate(context)
        assertTrue(original.edit().putString("encrypted-v1","test-ciphertext").commit())
        assertTrue(EncryptedProfilesIsolation.restore(context))
        assertFalse(original.contains("encrypted-v1"))
        assertEquals("preserved",original.getString("other",null))
    }
}
