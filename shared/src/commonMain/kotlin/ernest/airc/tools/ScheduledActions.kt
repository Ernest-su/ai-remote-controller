package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class ScheduledAction(val id: String, val action: String, @kotlinx.serialization.SerialName("package") val packageName: String? = null,
    val kind: String, val nextLocalTime: String = "", val dailyTime: String? = null, val nextAt: Long, val enabled: Boolean,
    val volumePercent: Int? = null, val lastAt: Long? = null, val lastStatus: String = "pending", val lastResult: String? = null)
@Serializable
data class ScheduleList(val tasks: List<ScheduledAction>, val targetNow: Long, val targetLocalTime: String, val utcOffsetSeconds: Long, val runningRequired: Boolean = true)
data class ScheduleState(val sessionKey: String? = null, val data: ScheduleList? = null, val busy: Boolean = false, val error: String? = null)
fun scheduledActionLabel(action: String, volumePercent: Int? = null) = when(action) { "set_volume" -> volumePercent?.let { "媒体音量调至 $it%" } ?: "调节媒体音量"; "mute" -> "静音"; "unmute" -> "解除静音"; "pause" -> "暂停播放"; "shutdown" -> "关机"; "stop_app" -> "退出应用"; "open_app" -> "打开应用"; else -> action }
fun scheduledStatusLabel(status: String) = when(status) { "pending" -> "等待执行"; "running" -> "执行中"; "submitted" -> "已提交操作"; "missed" -> "错过，已跳过"; "interrupted" -> "服务退出，结果不确定"; "uncertain" -> "执行超时，结果不确定"; "failed" -> "执行失败"; else -> status }
