package ernest.airc

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun read(): List<RememberedDevice> = runCatching {
        json.decodeFromString<List<RememberedDevice>>(preferences.getString("history", "[]")!!)
    }.getOrDefault(emptyList())
    @Synchronized fun remember(device: RememberedDevice, previousId: String? = null): List<RememberedDevice> {
        val result = rememberDevice(read(), device, previousId)
        check(preferences.edit().putString("history", json.encodeToString(result)).commit()) { "无法保存设备" }
        return result
    }
    @Synchronized fun rename(id: String, name: String): List<RememberedDevice> {
        val result = renameDevice(read(), id, name)
        check(preferences.edit().putString("history", json.encodeToString(result)).commit()) { "无法保存设备显示名" }
        return result
    }
    @Synchronized fun remove(id: String): List<RememberedDevice> {
        val result = read().filterNot { it.id == id }
        check(preferences.edit().putString("history", json.encodeToString(result)).commit())
        return result
    }
}
