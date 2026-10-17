package ernest.airc

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*

val LocalRemoteKeySender = staticCompositionLocalOf<RemoteKeySender?> { null }

/** Touch and keyboard produce edges; accessibility activation remains one finite tap. */
@Composable
fun Modifier.remoteKeyInput(key: String, enabled: Boolean, onClick: () -> Unit): Modifier {
    val sender = LocalRemoteKeySender.current
    val tap by rememberUpdatedState(onClick)
    val interactions = rememberRemoteKeyInteractions(enabled, key)
    val currentInteractions by rememberUpdatedState(interactions)
    var token by remember(key, sender) { mutableStateOf<String?>(null) }
    var press by remember(key, sender) { mutableStateOf<PressInteraction.Press?>(null) }
    var keyboardHeld by remember { mutableStateOf(false) }
    fun finish(cancelled: Boolean) {
        val current = press ?: return
        press = null
        currentInteractions.tryEmit(if (cancelled) PressInteraction.Cancel(current) else PressInteraction.Release(current))
        token?.let { sender?.up(it, cancelled) }
        token = null
        if (sender == null && !cancelled) tap()
    }
    fun start() {
        if (press != null) return
        press = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero).also(currentInteractions::tryEmit)
        token = sender?.down(key)
    }
    DisposableEffect(enabled, key, sender) { onDispose { finish(true) } }
    return this.indication(interactions, LocalIndication.current)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
            onClick { if (enabled) { tap(); true } else false }
        }
        .onFocusChanged { if (!it.isFocused && keyboardHeld) { keyboardHeld = false; finish(true) } }
        .onKeyEvent {
            if (enabled && it.key in listOf(Key.Spacebar, Key.Enter, Key.DirectionCenter)) {
                if (it.type == KeyEventType.KeyDown) { keyboardHeld = true; start() }
                else if (it.type == KeyEventType.KeyUp) { keyboardHeld = false; finish(false) }
                true
            } else false
        }.focusable(enabled)
        .pointerInput(enabled, key, sender) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var cancelled = true
                try {
                    down.consume(); start()
                    while (true) {
                        val change = awaitPointerEvent().changes.find { it.id == down.id } ?: break
                        val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                        if (change.isConsumed || !inside) break
                        if (!change.pressed) { cancelled = false; break }
                    }
                } finally { finish(cancelled) }
            }
        }
}
