package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ModelProfileEditor(controller: ModelProfileActions, connected: Boolean, configured: Boolean, onSync: () -> Unit) {
    val profiles by controller.state.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    PageContent(Modifier.testTag("model-page")) {
        Panel {
            SectionTitle("App 模型配置", "按 API 地址保存配置；同步到目标设备需手动操作。")
            profiles.profiles.forEach { profile ->
                TextButton({ visible = false; controller.select(profile.baseUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("model-profile-${profile.baseUrl}").semantics { stateDescription = if (profile.baseUrl == profiles.draft.baseUrl) "当前配置" else "已保存配置" }) {
                    Text(profile.baseUrl)
                }
                TextButton({ visible = false; controller.delete(profile.baseUrl) }, modifier = Modifier.heightIn(min = 48.dp).testTag("delete-model-profile-${profile.baseUrl}"),
                    colors = ButtonDefaults.textButtonColors(contentColor = RemoteColors.Warning)) { Text("删除此配置") }
                HorizontalDivider(color = RemoteColors.Divider)
            }
            OutlinedButton({ visible = false; controller.select(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("新增配置") }
            OutlinedTextField(profiles.draft.baseUrl, controller::editUrl, label = { Text("OpenAI 兼容 API 地址") },
                modifier = Modifier.fillMaxWidth().testTag("model-api-url"), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            OutlinedTextField(profiles.draft.apiKey, controller::editKey, label = { Text("API Key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ visible = !visible }, modifier = Modifier.heightIn(min = 48.dp).testTag("model-key-visibility")) { Text(if (visible) "隐藏" else "显示") } },
                modifier = Modifier.fillMaxWidth().testTag("model-api-key"), singleLine = true)
            OutlinedButton(controller::fetch, enabled = !profiles.fetching && profiles.draft.baseUrl.isNotBlank() && profiles.draft.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fetch-models"), shape = MaterialTheme.shapes.small) { Text(if (profiles.fetching) "正在拉取…" else "拉取 / 更新模型列表") }
            Text(if (profiles.draft.fetchedAt != null) "已缓存 ${profiles.draft.models.size} 个模型" else "未缓存模型列表，可手动输入模型名称", color = RemoteColors.Secondary)
            OutlinedTextField(profiles.draft.model, controller::editModel, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth().testTag("model-name"), singleLine = true)
            OutlinedButton({ choosing = true }, enabled = profiles.draft.models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("从缓存列表选择模型") }
            ActionButton("保存到 App", profiles.draft.baseUrl.isNotBlank() && profiles.draft.apiKey.isNotBlank(), controller::save)
            if (profiles.message.isNotBlank()) Text(profiles.message, color = RemoteColors.Secondary, modifier = Modifier.testTag("model-profile-message"))
        }
        Panel {
            SectionTitle("目标设备模型")
            Text(if (!connected) "连接目标设备后可查看配置状态并手动更新；本地配置仍可编辑。" else if (configured) "目标已配置模型服务" else "目标尚未配置模型服务", color = RemoteColors.Secondary)
            ActionButton(if (profiles.syncing) "正在更新…" else "更新到目标设备",
                connected && !profiles.syncing && profiles.draft.baseUrl.isNotBlank() && profiles.draft.apiKey.isNotBlank() && profiles.draft.model.isNotBlank(), onSync)
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择模型") },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(profiles.draft.models, key = { it }) { name ->
                TextButton({ controller.editModel(name); choosing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(name) }
            }
        } }, confirmButton = { TextButton({ choosing = false }) { Text("关闭") } })
}
