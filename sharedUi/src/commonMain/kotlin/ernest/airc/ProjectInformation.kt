package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object ProjectLinks {
    const val DOCUMENTATION = "https://suqishuo.cn/ai-remote-controller/"
}

@Composable
fun ProjectAbout(version: String, onDocumentation: () -> Unit) = PageContent(Modifier.testTag("about-page")) {
    Panel {
        SectionTitle("AI 遥控")
        InfoRow("版本", version)
        HorizontalDivider(color = RemoteColors.Divider)
        SectionTitle("项目文档（Pages）")
        SelectionContainer { Text(ProjectLinks.DOCUMENTATION, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary) }
        TextButton(onDocumentation, Modifier.heightIn(min = 48.dp).testTag("about-pages")) { Text("打开 Pages 页面") }
    }
}

@Composable
fun ProjectDocumentation(onOpen: () -> Unit) = Panel {
    SectionTitle("在线帮助")
    Text("项目文档包含快速开始、各项功能说明与常见问题。", style = MaterialTheme.typography.bodyMedium)
    SelectionContainer { Text(ProjectLinks.DOCUMENTATION, style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary) }
    TextButton(onOpen, Modifier.heightIn(min = 48.dp).testTag("help-pages")) { Text("项目文档（Pages）") }
}
