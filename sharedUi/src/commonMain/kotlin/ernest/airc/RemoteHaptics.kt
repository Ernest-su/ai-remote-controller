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
    private class Press(val timer: Job, var producerCompletion: DisposableHandle? = null)
    private val presses = mutableMapOf<PressInteraction.Press, Press>()
    private var disposed = false
    override val interactions = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        if (interaction is PressInteraction.Press) {
            val context = currentCoroutineContext()
            context.ensureActive()
            delegate.emit(interaction)
            context.ensureActive()
            feedback(interaction, context[Job])
        } else {
            // Release/cancel must stop hardware before indication dispatch can suspend.
            feedback(interaction)
            delegate.emit(interaction)
        }
    }
    override fun tryEmit(interaction: Interaction): Boolean {
        if (interaction !is PressInteraction.Press) feedback(interaction)
        val accepted = delegate.tryEmit(interaction)
        if (accepted && interaction is PressInteraction.Press) feedback(interaction)
        return accepted
    }
    private fun feedback(interaction: Interaction, producer: Job? = null) {
        if (disposed) return
        when (interaction) {
            is PressInteraction.Press -> {
                if (interaction in presses) return
                haptics.tap()
                val press = Press(scope.launch(start = CoroutineStart.LAZY) {
                    delay(longPressMillis)
                    haptics.startHold(interaction)
                })
                presses[interaction] = press
                // Compose can cancel delayed press dispatch on a quick release before it
                // records the token. That token may never receive a Release/Cancel event.
                press.producerCompletion = producer?.invokeOnCompletion { error ->
                    if (error != null) release(interaction)
                }
                if (presses[interaction] === press) press.timer.start()
            }
            is PressInteraction.Release -> release(interaction.press)
            is PressInteraction.Cancel -> release(interaction.press)
        }
    }
    private fun release(press: PressInteraction.Press) {
        presses.remove(press)?.let {
            it.producerCompletion?.dispose()
            it.timer.cancel()
        }
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
