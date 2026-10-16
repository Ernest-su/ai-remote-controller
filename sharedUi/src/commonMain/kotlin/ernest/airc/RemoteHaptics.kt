package ernest.airc

import androidx.compose.foundation.interaction.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.*

interface RemoteHaptics {
    fun trace(event: String) = Unit
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
    private val debugName: String = "remote-key",
) : MutableInteractionSource {
    private val delegate = MutableInteractionSource()
    private class Press(val timer: Job, var producerCompletion: DisposableHandle? = null)
    private val presses = mutableMapOf<PressInteraction.Press, Press>()
    private var disposed = false
    override val interactions = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        trace("emit ${describe(interaction)}")
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
        trace("tryEmit ${describe(interaction)}")
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
                // One source belongs to one key. Compose can emit a second press for
                // a quick release while its delayed press dispatch has just completed;
                // only that second token receives Release. Retire the displaced token.
                presses.keys.toList().forEach(::release)
                haptics.tap()
                trace("timer-start token=${interaction.hashCode()} timeout=$longPressMillis producer=${producer?.hashCode()}")
                val press = Press(scope.launch(start = CoroutineStart.LAZY) {
                    delay(longPressMillis)
                    trace("timer-fired token=${interaction.hashCode()} pending=${presses.size}")
                    haptics.startHold(interaction)
                })
                presses[interaction] = press
                // Compose can cancel delayed press dispatch on a quick release before it
                // records the token. That token may never receive a Release/Cancel event.
                press.producerCompletion = producer?.invokeOnCompletion { error ->
                    trace("producer-complete token=${interaction.hashCode()} cancelled=${error != null}")
                    if (error != null) release(interaction)
                }
                if (presses[interaction] === press) press.timer.start()
            }
            is PressInteraction.Release -> release(interaction.press)
            is PressInteraction.Cancel -> release(interaction.press)
        }
    }
    private fun release(press: PressInteraction.Press) {
        trace("release token=${press.hashCode()} registered=${press in presses}")
        presses.remove(press)?.let {
            it.producerCompletion?.dispose()
            it.timer.cancel()
        }
        haptics.stopHold(press)
    }
    fun dispose() {
        trace("dispose pending=${presses.size}")
        disposed = true
        presses.keys.toList().forEach(::release)
    }
    private fun trace(event: String) = haptics.trace("$debugName source=${hashCode()} $event")
    private fun describe(interaction: Interaction): String = when (interaction) {
        is PressInteraction.Press -> "press token=${interaction.hashCode()}"
        is PressInteraction.Release -> "up token=${interaction.press.hashCode()}"
        is PressInteraction.Cancel -> "cancel token=${interaction.press.hashCode()}"
        else -> interaction::class.simpleName.orEmpty()
    }
}

@Composable
fun rememberRemoteKeyInteractions(enabled: Boolean, debugName: String = "remote-key"): MutableInteractionSource {
    val scope = rememberCoroutineScope()
    val haptics = LocalRemoteHaptics.current
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val source = remember(scope, enabled, haptics, timeout, debugName) {
        RemoteKeyInteractionSource(scope, if (enabled) haptics else SilentRemoteHaptics, timeout, debugName)
    }
    DisposableEffect(source) { onDispose { source.dispose() } }
    return source
}
