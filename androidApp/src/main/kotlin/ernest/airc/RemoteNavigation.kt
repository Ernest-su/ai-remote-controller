package ernest.airc

internal data class RemoteFeature(val page: RemotePage, val icon: Int)

internal val remoteFeatures = listOf(
    RemoteFeature(RemotePage.APPS, R.drawable.ic_apps),
    RemoteFeature(RemotePage.TEXT, R.drawable.ic_text),
    RemoteFeature(RemotePage.MODEL, R.drawable.ic_model),
    RemoteFeature(RemotePage.SERVICE, R.drawable.ic_service),
    RemoteFeature(RemotePage.PERMISSIONS, R.drawable.ic_permissions),
    RemoteFeature(RemotePage.DEVICE_INFO, R.drawable.ic_device_info),
    RemoteFeature(RemotePage.RESULTS, R.drawable.ic_results),
    RemoteFeature(RemotePage.HELP, R.drawable.ic_help),
    RemoteFeature(RemotePage.SCREENSHOT, R.drawable.ic_screenshot),
    RemoteFeature(RemotePage.FILES, R.drawable.ic_files),
    RemoteFeature(RemotePage.SCHEDULES, R.drawable.ic_clock),
    RemoteFeature(RemotePage.SETTINGS, R.drawable.ic_settings),
    RemoteFeature(RemotePage.ABOUT, R.drawable.ic_about),
)

internal val remoteTypes = listOf(
    RemoteFeature(RemotePage.REMOTE, R.drawable.ic_remote_types),
    RemoteFeature(RemotePage.TOUCHPAD, R.drawable.ic_touchpad),
    RemoteFeature(RemotePage.KEYPAD, R.drawable.ic_keypad),
    RemoteFeature(RemotePage.MEDIA_CONTROL, R.drawable.ic_media),
    RemoteFeature(RemotePage.GAMEPAD, R.drawable.ic_gamepad),
)

internal fun RemotePage.androidBackDestination(deviceOrigin: RemotePage): RemotePage =
    if (this != RemotePage.REMOTE && remoteTypes.any { it.page == this }) RemotePage.REMOTE_TYPES
    else backDestination(deviceOrigin)
