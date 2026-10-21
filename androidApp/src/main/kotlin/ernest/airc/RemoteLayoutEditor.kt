package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteLayoutEditor(state: RemoteUiState, model: RemoteViewModel, close: () -> Unit) {
    var choosingApp by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(state.connected,state.selected) {if(state.connected && state.apps.isEmpty())model.listApps()}
    LibraryDialog("自定义遥控按键", "remote-layout-editor", close, subtitle=state.history.find {it.id==state.selected}?.visibleName) {
        if(choosingApp) ApplicationLibraryPanel(state.apps, emptyList(),state.connected,state.appsLoading,state.appsError,model::listApps,
            {app ->model.addRemoteShortcut(RemoteShortcut("app",app.packageName,(app.label ?: app.packageName).take(40)));choosingApp=false},selectionAvailable=state.selected!=null)
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("为这台电视添加常用按键与应用入口，按保存顺序显示在标准遥控器中。最多8项；基础遥控按键保持可用。",color=RemoteColors.Secondary)
            if(state.selected==null)Text("请先选择一台电视",color=RemoteColors.Secondary)
            state.remoteLayout.shortcuts.forEachIndexed {index,item ->
                Surface(color=RemoteColors.Surface) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(item.label,style=MaterialTheme.typography.bodyLarge)
                        Row {
                            IconButton({model.saveRemoteLayout(state.remoteLayout.move(index,-1))},enabled=index>0,modifier=Modifier.size(48.dp)){Icon(painterResource(R.drawable.ic_up),"上移 ${item.label}")}
                            IconButton({model.saveRemoteLayout(state.remoteLayout.move(index,1))},enabled=index<state.remoteLayout.shortcuts.lastIndex,modifier=Modifier.size(48.dp)){Icon(painterResource(R.drawable.ic_down),"下移 ${item.label}")}
                            TextButton({model.saveRemoteLayout(state.remoteLayout.copy(shortcuts=state.remoteLayout.shortcuts.filterIndexed {i,_->i!=index}))},modifier=Modifier.heightIn(min=48.dp)){Text("移除",color=RemoteColors.Warning)}
                        }
                    }
                }
            }
            OutlinedButton({choosingApp=true},enabled=state.selected!=null&&state.remoteLayout.shortcuts.size<8,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("添加应用快捷键")}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                configurableRemoteKeys.forEach {key ->
                    FilterChip(state.remoteLayout.shortcuts.any {it.kind=="key"&&it.value==key}, {model.addRemoteShortcut(RemoteShortcut("key",key,configurableKeyLabel(key)))},
                        enabled=state.selected!=null&&state.remoteLayout.shortcuts.size<8&&state.remoteLayout.shortcuts.none {it.kind=="key"&&it.value==key},
                        label={Text(configurableKeyLabel(key))},modifier=Modifier.heightIn(min=48.dp))
                }
            }
        }
    }
}

@Composable
internal fun CustomRemoteKeys(state: RemoteUiState, model: RemoteViewModel) {
    state.remoteLayout.shortcuts.chunked(3).forEach {row ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            row.forEach {item ->
                OutlinedButton({if(item.kind=="app")model.openApp(item.value)else model.key(item.value)},enabled=state.connected,
                    modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=MaterialTheme.shapes.small) {
                    Icon(painterResource(if(item.kind=="app")R.drawable.ic_apps else R.drawable.ic_remote_types),null,Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp));Text(item.label,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            repeat(3-row.size){Spacer(Modifier.weight(1f))}
        }
    }
}
