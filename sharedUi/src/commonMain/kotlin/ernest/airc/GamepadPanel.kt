package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GamepadPanel(state: GamepadControlState,available: Boolean,onPulse: (GamepadPulse)->Unit,onReset: ()->Unit, continuous: @Composable ColumnScope.() -> Unit = {}) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var selectedText by rememberSaveable { mutableStateOf("") }
    var x by rememberSaveable { mutableStateOf(0f) }; var y by rememberSaveable { mutableStateOf(0f) }
    var hold by rememberSaveable { mutableStateOf(100) }
    val selected = selectedText.split(',').filter { it.isNotEmpty() }
    PageContent(Modifier.testTag("gamepad-page"), contentPadding = 8.dp) {
        continuous()
        TextButton({ advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp).testTag("gamepad-advanced")) { Text(if (advanced) "收起按键与组合设置" else "更多按键与组合设置") }
        if (advanced) Panel {
            SectionTitle("游戏手柄（输入原型）", "输入发送到当前焦点应用；这是有限时长操作，结束后释放并回中。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100,300,1000).forEach { duration -> FilterChip(hold == duration,{hold=duration},enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp),label = { Text("$duration 毫秒") }) }
            }
            listOf(listOf("up","left","down","right"),listOf("a","b","x","y"),listOf("l1","r1","l2","r2"),listOf("start","select","thumb_l","thumb_r")).forEach { row ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { button -> OutlinedButton({onPulse(GamepadPulse(listOf(button),holdMs=hold))},enabled=available&&!state.busy,
                        modifier=Modifier.heightIn(min=48.dp).testTag("gamepad-$button"), shape = MaterialTheme.shapes.small) { Text(gamepadLabel(button)) } }
                }
            }
            Text("组合按钮（最多四个）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gamepadButtons.filter { it !in listOf("up","down","left","right") }.forEach { button ->
                    FilterChip(button in selected, {selectedText=(if(button in selected) selected-button else selected+button).joinToString(",")},
                        enabled=!state.busy&&(button in selected||selected.size<4),modifier=Modifier.heightIn(min=48.dp).testTag("gamepad-combine-$button"),label={Text(gamepadLabel(button))})
                }
            }
            Text("摇杆 X：${x.toDouble()} · Y：${y.toDouble()}",style=MaterialTheme.typography.bodySmall)
            Slider(x,{x=it},enabled=!state.busy,valueRange=-1f..1f,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("gamepad-axis-x"))
            Slider(y,{y=it},enabled=!state.busy,valueRange=-1f..1f,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("gamepad-axis-y"))
            val pulse = GamepadPulse(selected,x.toDouble(),y.toDouble(),hold)
            ActionButton("发送组合与摇杆",available&&!state.busy&&pulse.valid()) {onPulse(pulse)}
            TextButton({x=0f;y=0f;selectedText=""},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp)) {Text("重置输入草稿")}
            ActionButton("释放按钮并回中",available&&!state.busy,onReset)
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("合成输入不注册硬件手柄，部分游戏会忽略；请求成功不代表游戏支持。断线不撤销已接受的有限输入，服务仍尝试释放。",color=RemoteColors.Secondary)
            state.message?.let {Text(it,color=RemoteColors.Secondary)};state.error?.let {Text(it,color=RemoteColors.Warning)}
        }
    }
}
private fun gamepadLabel(button:String)=when(button){"up"->"上";"down"->"下";"left"->"左";"right"->"右";"thumb_l"->"左摇杆键";"thumb_r"->"右摇杆键";else->button.uppercase()}
