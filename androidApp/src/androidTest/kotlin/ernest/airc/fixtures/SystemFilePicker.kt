package ernest.airc

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue

/** Bounded platform picker navigation; callers identify only their own UUID files. */
internal class SystemFilePicker(private val instrumentation:Instrumentation) {
    private var stage="picker"
    private suspend fun await(condition:()->Boolean){try{withTimeout(15000){while(!condition())delay(50)}}catch(error:TimeoutCancellationException){throw AssertionError("Timed out at $stage",error)}}
    fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            fun visit(value:AccessibilityNodeInfo?):AccessibilityNodeInfo?{
                if(value==null)return null
                if(predicate(value))return value
                for(i in 0 until value.childCount){visit(value.getChild(i))?.let{return it}}
                return null
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
    fun click(value:AccessibilityNodeInfo){var current:AccessibilityNodeInfo?=value;while(current!=null){if(current.isClickable){assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK));return};current=current.parent};error("No clickable picker entry")}
    private fun tryClick(value:AccessibilityNodeInfo):Boolean {
        var current:AccessibilityNodeInfo?=value
        while(current!=null){if(current.isClickable && current.isEnabled)return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);current=current.parent}
        return false
    }
    private fun clickable(value:AccessibilityNodeInfo):Boolean {
        if(!value.isVisibleToUser)return false
        var current:AccessibilityNodeInfo?=value
        while(current!=null){if(current.isClickable && current.isEnabled)return true;current=current.parent}
        return false
    }
    suspend fun saveButton():AccessibilityNodeInfo {
        stage="save button"
        fun matches(value:AccessibilityNodeInfo):Boolean = value.isEnabled && clickable(value) &&
            (value.text?.toString()=="保存" || value.text?.toString()?.equals("save",ignoreCase=true)==true)
        await { node(::matches)!=null }
        return checkNotNull(node(::matches))
    }
    suspend fun filenameField():AccessibilityNodeInfo {
        stage="filename field"
        fun matches(value:AccessibilityNodeInfo):Boolean = value.isVisibleToUser && value.isEnabled && value.className?.toString()=="android.widget.EditText"
        await { node(::matches)!=null }
        return checkNotNull(node(::matches))
    }
    suspend fun locate(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
            repeat(30){
                node(predicate)?.let{return it}
                node{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(300)
            }
            error("Owned picker entry not found")
        }
    suspend fun downloads(){
            stage="picker navigation";println("picker-stage: open Downloads roots")
            fun roots(value:AccessibilityNodeInfo):Boolean {
                val label=value.contentDescription?.toString() ?: return false
                return label.contains("显示根")||label.contains("显示位置")||label.contains("打开导航")||label.contains("Show roots")||label.contains("Show navigation")
            }
            await{node(::roots)!=null || instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.coloros.filemanager"}
            if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.coloros.filemanager") {
                node{it.text?.toString()=="文件"}?.let(::click)
                await{node{it.text?.toString()=="全部文件"}!=null}
                click(checkNotNull(node{it.text?.toString()=="全部文件"}))
                delay(300)
                click(locate{it.text?.toString() in listOf("Download","下载","Downloads")})
                return
            }
            click(checkNotNull(node(::roots)))
            fun downloadEntry(value:AccessibilityNodeInfo):Boolean =
                value.text?.toString() in listOf("下载","Downloads") && clickable(value)
            await{node(::downloadEntry)?.let(::tryClick)==true}
            stage="Downloads content"
            await{node(::downloadEntry)==null}
        }

}
