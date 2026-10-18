package ernest.airc

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Prevent UI fixtures from automatically reconnecting to real saved targets. */
internal class DeviceHistoryIsolation : ExternalResource() {
    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backup = context.getSharedPreferences("device-history-test-backup", 0)
        check(!backup.getBoolean("active", false)) { "存在未恢复的设备测试备份，请调用 DeviceHistoryIsolation.restore 后重试" }
        val history = context.getSharedPreferences("devices", 0).getString("history", null)
        val last = context.getSharedPreferences("runtime-release", 0).getString("last-connected-device", null)
        val edit = backup.edit().clear().putBoolean("active", true)
        history?.let { edit.putString("history", it) }
        last?.let { edit.putString("last-connected-device", it) }
        check(edit.commit()) { "无法备份设备历史" }
        check(context.getSharedPreferences("devices", 0).edit().putString("history", "[]").commit())
        check(context.getSharedPreferences("runtime-release", 0).edit().remove("last-connected-device").commit())
    }
    override fun after() {
        check(restore(InstrumentationRegistry.getInstrumentation().targetContext)) { "设备历史备份缺失" }
    }
    companion object {
        fun restore(context: Context): Boolean {
            val backup = context.getSharedPreferences("device-history-test-backup", 0)
            if (!backup.getBoolean("active", false)) return false
            val history = context.getSharedPreferences("devices", 0).edit()
            backup.getString("history", null)?.let { history.putString("history", it) } ?: history.remove("history")
            check(history.commit()) { "无法恢复设备历史，备份保留" }
            val release = context.getSharedPreferences("runtime-release", 0).edit()
            backup.getString("last-connected-device", null)?.let { release.putString("last-connected-device", it) } ?: release.remove("last-connected-device")
            check(release.commit()) { "无法恢复上次设备，备份保留" }
            check(backup.edit().clear().commit())
            return true
        }
    }
}
