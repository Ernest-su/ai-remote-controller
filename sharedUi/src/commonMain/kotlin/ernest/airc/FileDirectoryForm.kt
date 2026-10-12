package ernest.airc

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun FileDirectoryForm(root:String,path:String,available:Boolean,onCreate:(String)->Unit) {
    var name by rememberSaveable(root,path){mutableStateOf("")}
    OutlinedTextField(name,{name=it},label={Text("新目录名称")},singleLine=true,enabled=available,modifier=Modifier.fillMaxWidth().testTag("directory-name"))
    ActionButton("创建目录",available&&validTargetFileName(name),{onCreate(name)})
}
