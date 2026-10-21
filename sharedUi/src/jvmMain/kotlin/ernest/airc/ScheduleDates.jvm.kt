package ernest.airc

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

actual fun nextScheduleOnceTime(time: String, targetLocalTime: String, elapsedSeconds: Long): String? = runCatching {
    val now = LocalDateTime.parse(targetLocalTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")).plusSeconds(elapsedSeconds.coerceAtLeast(0))
    val selected = LocalTime.parse(time)
    val today = now.toLocalDate().atTime(selected)
    val next = if (today.isAfter(now)) today else today.plusDays(1)
    next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrNull()
