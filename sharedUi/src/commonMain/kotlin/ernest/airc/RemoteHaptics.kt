package ernest.airc

import androidx.compose.foundation.interaction.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.*

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

/** Stop feedback in the release dispatch itself, before ripple delivery or remote click actions. */
class RemoteKeyInteractionSource(
    private val scope: CoroutineScope,
    private val haptics: RemoteHaptics,
    private val longPressMillis: Long,
) : MutableInteractionSource {
    private val delegate = MutableInteractionSource()
    private val presses = mutableMapOf<PressInteraction.Press, Job>()
    private var disposed = false
    override val interactions = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        feedback(interaction)
        delegate.emit(interaction)
    }
    override fun tryEmit(interaction: Interaction): Boolean {
        feedback(interaction)
        return delegate.tryEmit(interaction)
    }
    private fun feedback(interaction: Interaction) {
        if (disposed) return
        when (interaction) {
            is PressInteraction.Press -> {
                haptics.tap()
                presses[interaction] = scope.launch {
                    delay(longPressMillis)
                    haptics.startHold(interaction)
                }
            }
            is PressInteraction.Release -> release(interaction.press)
            is PressInteraction.Cancel -> release(interaction.press)
        }
    }
    private fun release(press: PressInteraction.Press) {
        presses.remove(press)?.cancel()
        haptics.stopHold(press)
    }
    fun dispose() {
        disposed = true
        presses.keys.toList().forEach(::release)
    }
}

@Composable
fun rememberRemoteKeyInteractions(enabled: Boolean): MutableInteractionSource {
    val scope = rememberCoroutineScope()
    val haptics = LocalRemoteHaptics.current
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val source = remember(scope, enabled, haptics, timeout) {
        RemoteKeyInteractionSource(scope, if (enabled) haptics else SilentRemoteHaptics, timeout)
    }
    DisposableEffect(source) { onDispose { source.dispose() } }
    return source
}
