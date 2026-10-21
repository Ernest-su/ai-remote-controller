package ernest.airc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LauncherAction(val token: String, val device: String, val kind: String, val value: String, val label: String, val icon: String = "default")

internal object LauncherActions {
    private val json = Json { ignoreUnknownKeys = true }
    fun all(context: Context): List<LauncherAction> = runCatching {
        json.decodeFromString<List<LauncherAction>>(context.getSharedPreferences("launcher-actions", 0).getString("items", "[]")!!)
    }.getOrDefault(emptyList()).take(32)
    fun resolve(context: Context, intent: Intent?): LauncherAction? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "airc-action" || uri.host != context.packageName) return null
        return all(context).find { it.token == uri.lastPathSegment }
    }
    fun intent(context: Context, action: LauncherAction) = Intent(context, MainActivity::class.java).apply {
        this.action = Intent.ACTION_VIEW
        data = Uri.Builder().scheme("airc-action").authority(context.packageName).appendPath(action.token).build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    fun pin(context: Context, device: String, kind: String, value: String, label: String, icon: String): Boolean {
        require(validLauncherName(label) && launcherIcons.any { it.id == icon })
        require(kind in listOf("app", "routine"))
        val previous = all(context)
        val action = previous.find { it.device == device && it.kind == kind && it.value == value }
            ?.copy(label = label, icon = icon) ?: LauncherAction(java.util.UUID.randomUUID().toString(), device, kind, value, label, icon)
        val actions = (listOf(action) + previous.filterNot { it.token == action.token }).take(32)
        check(context.getSharedPreferences("launcher-actions", 0).edit().putString("items", json.encodeToString(actions)).commit())
        AircRemoteWidget.updateAll(context)
        val manager = context.getSystemService(ShortcutManager::class.java)
        manager.setDynamicShortcuts(actions.take(minOf(4, manager.maxShortcutCountPerActivity)).map { shortcut(context, it) })
        manager.updateShortcuts(listOf(shortcut(context, action)))
        if (!manager.isRequestPinShortcutSupported) return false
        return manager.requestPinShortcut(shortcut(context, action), null)
    }
    internal fun shortcut(context: Context, action: LauncherAction): ShortcutInfo = ShortcutInfo.Builder(context, action.token)
        .setShortLabel(action.label.take(40)).setLongLabel(action.label)
        .setIcon(Icon.createWithResource(context, launcherIcon(action.icon).resource)).setIntent(intent(context, action)).build()
    fun forget(context: Context, device: String, routine: String? = null) {
        val actions = all(context)
        val removed = actions.filter { it.device == device && (routine == null || it.kind == "routine" && it.value == routine) }
        context.getSharedPreferences("launcher-actions", 0).edit().putString("items", json.encodeToString(actions - removed.toSet())).apply()
        if (removed.isNotEmpty()) {
            val manager=context.getSystemService(ShortcutManager::class.java)
            manager.removeDynamicShortcuts(removed.map { it.token })
            manager.disableShortcuts(removed.map { it.token }, "目标设备或快捷操作已删除")
        }
        AircRemoteWidget.updateAll(context)
    }
}

class AircRemoteWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) updateAll(context)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, AircRemoteWidget::class.java)).forEach { update(context, manager, it) }
        }
        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.remote_widget)
            val mode=ThemeMode.fromStored(context.getSharedPreferences("appearance",0).getString("theme",null))
            val systemDark=context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val palette=if(mode.isDark(systemDark)) DarkRemotePalette else LightRemotePalette
            views.setInt(R.id.widget_root,"setBackgroundColor",palette.Background.toArgb())
            views.setTextColor(R.id.widget_title,palette.Text.toArgb())
            views.setTextColor(R.id.widget_empty,palette.Secondary.toArgb())
            views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val actions = LauncherActions.all(context).take(4)
            listOf(R.id.widget_action_1, R.id.widget_action_2, R.id.widget_action_3, R.id.widget_action_4).forEachIndexed { i, view ->
                val action = actions.getOrNull(i)
                views.setViewVisibility(view, if (action == null) android.view.View.GONE else android.view.View.VISIBLE)
                if (action != null) {
                    views.setInt(view,"setBackgroundColor",palette.Surface.toArgb())
                    views.setTextColor(view,palette.Text.toArgb())
                    views.setTextViewText(view, action.label)
                    views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, 0, LauncherActions.intent(context, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            views.setViewVisibility(R.id.widget_empty, if (actions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
            manager.updateAppWidget(id, views)
        }
    }
}
