package ernest.airc

fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return if (minutes < 60) "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun parsePlaybackTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.length > 6 || it.any { c -> c !in '0'..'9' } }) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    if (numbers.drop(1).any { it > 59 }) return null
    return numbers.fold(0L) { total, number -> total * 60 + number } * 1000
}

fun relativePlaybackPosition(position: Long, seconds: Long, duration: Long?): Long {
    val delta = seconds.coerceIn(-30, 30) * 1000
    val start = position.coerceAtLeast(0)
    val next = if (delta > 0 && start > Long.MAX_VALUE - delta) Long.MAX_VALUE else start + delta
    return next.coerceIn(0, duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
}
