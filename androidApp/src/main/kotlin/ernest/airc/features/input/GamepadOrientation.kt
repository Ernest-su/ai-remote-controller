package ernest.airc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.hostActivity():Activity?=when(this){
    is Activity->this
    is ContextWrapper->baseContext.hostActivity()
    else->null
}

/** Page-scoped orientation; saved original policy survives the induced Activity recreation. */
@Composable
internal fun GamepadLandscapeOrientation(){
    val activity=LocalContext.current.hostActivity() ?: return
    val original by rememberSaveable{mutableIntStateOf(activity.requestedOrientation)}
    DisposableEffect(activity){
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose{
            if(!activity.isChangingConfigurations && activity.requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                activity.requestedOrientation=original
        }
    }
}
