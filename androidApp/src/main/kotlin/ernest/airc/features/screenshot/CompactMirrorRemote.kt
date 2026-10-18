package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun CompactMirrorRemote(state: RemoteUiState, model: RemoteViewModel, modifier: Modifier) {
    RemoteKeyLifecycle(model.remoteKeys)
    CompositionLocalProvider(LocalRemoteKeySender provides model.remoteKeys) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("compact-mirror-remote").padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            @Composable fun Key(key: String, label: String, icon: Int) {
                Box(Modifier.size(48.dp).remoteKeyInput(key, state.connected) { model.key(key) }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), label, Modifier.size(24.dp), tint = if (state.connected) RemoteColors.Action else RemoteColors.Disabled)
                }
            }
            Row { Key("volume_down", "音量 −", R.drawable.ic_volume_down); Key("mute", "静音", R.drawable.ic_mute); Key("volume_up", "音量 +", R.drawable.ic_volume_up) }
            Row { Spacer(Modifier.size(48.dp)); Key("up", "向上", R.drawable.ic_up); Spacer(Modifier.size(48.dp)) }
            Row { Key("left", "向左", R.drawable.ic_left)
                Box(Modifier.size(48.dp).remoteKeyInput("ok", state.connected) { model.key("ok") }, contentAlignment = Alignment.Center) { Text("OK", color = if (state.connected) RemoteColors.Action else RemoteColors.Disabled) }
                Key("right", "向右", R.drawable.ic_right) }
            Row { Spacer(Modifier.size(48.dp)); Key("down", "向下", R.drawable.ic_down); Spacer(Modifier.size(48.dp)) }
            Row { Key("back", "返回", R.drawable.ic_back); Key("home", "主页", R.drawable.ic_home); Key("menu", "设备菜单", R.drawable.ic_device_menu) }
            Row { Key("media_previous", "上一曲", R.drawable.ic_previous); Key("play_pause", "播放 / 暂停", R.drawable.ic_play_pause); Key("media_next", "下一曲", R.drawable.ic_next) }
        }
    }
}
