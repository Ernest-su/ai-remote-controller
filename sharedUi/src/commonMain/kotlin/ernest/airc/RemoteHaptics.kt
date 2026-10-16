package ernest.airc

import androidx.compose.foundation.interaction.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

interface RemoteHaptics {
    fun tap()
    fun startHold(key: Any)
    fun stopHold(key: Any)
}

private object SilentRemoteHaptics : RemoteHaptics {
    override fun tap() = Unit
    override fun startHold(key: Any) = Unit
    override fun stopHold(key: Any) = Unit
}

val LocalRemoteHaptics = staticCompositionLocalOf<RemoteHaptics> { SilentRemoteHaptics }

/** Observe existing button interactions without consuming gestures or changing remote actions. */
@Composable
fun rememberRemoteKeyInteractions(enabled: Boolean): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalRemoteHaptics.current
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    LaunchedEffect(source, enabled, haptics, timeout) {
        if (enabled) observeRemoteKeyHaptics(source.interactions, haptics, timeout)
    }
    return source
}

suspend fun observeRemoteKeyHaptics(interactions: Flow<Interaction>, haptics: RemoteHaptics, longPressMillis: Long) = coroutineScope {
    val presses = mutableMapOf<PressInteraction.Press, Job>()
    try {
        interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    haptics.tap()
                    presses[event] = launch { delay(longPressMillis); haptics.startHold(event) }
                }
                is PressInteraction.Release -> presses.remove(event.press)?.let { job ->
                    job.cancelAndJoin()
                    haptics.stopHold(event.press)
                }
                is PressInteraction.Cancel -> presses.remove(event.press)?.let { job ->
                    job.cancelAndJoin()
                    haptics.stopHold(event.press)
                }
                else -> Unit
            }
        }
    } finally {
        presses.forEach { (press, job) -> job.cancel(); haptics.stopHold(press) }
    }
}
