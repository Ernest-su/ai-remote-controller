package ernest.airc

enum class RemotePage(val title: String) {
    REMOTE("AI 遥控"), REMOTE_TYPES("遥控器类型"), DEVICES("设备"), MENU("功能"), APPS("应用管理"), APP_DETAILS("应用详情"), TEXT("文字指令"),
    MODEL("模型配置"), SERVICE("服务管理"), PERMISSIONS("运行权限"),
    MEDIA_CONTROL("播放控制"), GAMEPAD("游戏手柄"), FILES("文件管理"), SCREENSHOT("设备预览"), TOUCHPAD("触控板"), KEYPAD("数字键盘"), DEVICE_INFO("设备信息"), RESULTS("执行结果"), SETTINGS("设置"), SCHEDULES("定时操作"), HELP("使用帮助");

    fun backDestination(deviceOrigin: RemotePage): RemotePage = when (this) {
        REMOTE, REMOTE_TYPES, MENU -> REMOTE
        APP_DETAILS -> APPS
        DEVICES -> deviceOrigin.takeUnless { it == DEVICES } ?: REMOTE
        else -> MENU
    }
}
