package ernest.airc

private val mediaJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

fun structuredMediaStatusLabel(payload: String): String {
    val sessions = mediaJson.decodeFromString<MediaSessions>(payload).sessions
    val session = sessions.firstOrNull { it.active && it.state == 3 }
        ?: sessions.firstOrNull { it.active } ?: return "暂无媒体播放"
    val label = when (session.state) {
        3 -> "正在播放"
        2 -> "已暂停"
        1 -> "已停止"
        6, 8 -> "正在缓冲"
        7 -> "播放错误"
        else -> "未播放"
    }
    return session.description?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
}

/** Use reported MediaSession state rather than inferring playback from key presses. */
fun mediaStatusLabel(dump: String): String {
    if (dump.contains("Permission Denial", ignoreCase = true) || dump.contains("permission denied", ignoreCase = true)) {
        return "无权限读取播放状态"
    }
    val sessions = dump.split(Regex("(?m)^\\s*[^\\n]+ tag=[^\\n]*$"))
        .mapNotNull { block ->
            val state = Regex("PlaybackState\\s*\\{state=(?:[A-Z_]+\\()?([0-9]+)").find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val description = Regex("(?m)^\\s*metadata:.*?description=(.*)$").find(block)?.groupValues?.get(1)?.trim()
            state to description
        }
    val session = sessions.firstOrNull { it.first == 3 } ?: sessions.firstOrNull() ?: return "暂无媒体播放"
    val label = when (session.first) {
        3 -> "正在播放"
        2 -> "已暂停"
        1 -> "已停止"
        6, 8 -> "正在缓冲"
        7 -> "播放错误"
        else -> "未播放"
    }
    return session.second?.takeIf { it.isNotBlank() && it != "null" }?.let { "$label · $it" } ?: label
}
