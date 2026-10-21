package ernest.airc

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileTaskTabs(groups: List<FileTaskGroup>, selected: FileTaskType, onSelect: (FileTaskType) -> Unit) {
    val index=groups.indexOfFirst {it.type==selected}.coerceAtLeast(0)
    SecondaryScrollableTabRow(index,edgePadding=0.dp,containerColor=RemoteColors.Surface,contentColor=RemoteColors.Action,
        divider={HorizontalDivider(color=RemoteColors.Divider,thickness=0.5.dp)},
        indicator={ if(groups.any {it.count>0}) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(index),color=RemoteColors.Action) }) {
        groups.forEach { group -> Tab(selected=group.type==selected && group.count>0,enabled=group.count>0,onClick={onSelect(group.type)},
            modifier=Modifier.heightIn(min=48.dp).testTag("file-task-tab-${group.type.name.lowercase()}"),
            selectedContentColor=RemoteColors.Action,unselectedContentColor=if(group.count==0) RemoteColors.Disabled else RemoteColors.Secondary,
            text={Text("${group.type.title}(${group.count})",maxLines=2)}) }
    }
}
