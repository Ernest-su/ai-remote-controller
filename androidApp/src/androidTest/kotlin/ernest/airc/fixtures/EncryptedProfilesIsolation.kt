package ernest.airc

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Stores only original Keystore ciphertext; survives an interrupted test process. */
internal class EncryptedProfilesIsolation : ExternalResource() {
    override fun before() {
        isolate(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    internal fun isolate(context:Context) {
        val backup=context.getSharedPreferences("model-profiles-test-backup",0)
        check(!backup.getBoolean("active",false)) { "存在未恢复的模型测试备份，请先显式恢复" }
        val original=context.getSharedPreferences("model-profiles",0)
        val encrypted=original.getString("encrypted-v1",null)
        val edit=backup.edit().clear().putBoolean("active",true)
        encrypted?.let{edit.putString("encrypted-v1",it)}
        check(edit.commit()) { "无法备份原加密配置" }
        check(original.edit().remove("encrypted-v1").commit()) { "无法隔离测试配置；原加密备份保留" }
    }

    override fun after() {
        check(restore(InstrumentationRegistry.getInstrumentation().targetContext)) { "模型测试备份缺失" }
    }

    companion object {
        fun restore(context:Context):Boolean {
            val backup=context.getSharedPreferences("model-profiles-test-backup",0)
            if(!backup.getBoolean("active",false))return false
            val encrypted=backup.getString("encrypted-v1",null)
            val edit=context.getSharedPreferences("model-profiles",0).edit()
            encrypted?.let{edit.putString("encrypted-v1",it)} ?: edit.remove("encrypted-v1")
            check(edit.commit()) { "无法恢复原加密配置；备份保留" }
            check(backup.edit().clear().commit()) { "原配置已恢复，但备份尚未清理" }
            return true
        }
    }
}
