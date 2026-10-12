package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class RemoteNavigationTest {
    @Test fun deviceManagementReturnsToThePageThatOpenedIt() {
        for (origin in listOf(RemotePage.REMOTE, RemotePage.MENU, RemotePage.MODEL, RemotePage.APPS)) {
            assertEquals(origin, RemotePage.DEVICES.backDestination(origin))
        }
    }
    @Test fun featurePagesReturnToTheMenuAndMenuReturnsHome() {
        assertEquals(RemotePage.REMOTE, RemotePage.MENU.backDestination(RemotePage.REMOTE))
        for (feature in remoteFeatures) {
            assertEquals(RemotePage.MENU, feature.page.backDestination(RemotePage.REMOTE))
        }
    }
    @Test fun invalidDeviceOriginCannotTrapTheUserOnDevices() {
        assertEquals(RemotePage.REMOTE, RemotePage.DEVICES.backDestination(RemotePage.DEVICES))
    }
    @Test fun remoteTypesAreSeparateFromFeaturesAndReturnThroughPicker() {
        assertEquals(setOf(RemotePage.REMOTE, RemotePage.TOUCHPAD, RemotePage.KEYPAD, RemotePage.MEDIA_CONTROL, RemotePage.GAMEPAD), remoteTypes.map { it.page }.toSet())
        assertTrue(remoteFeatures.none { feature -> remoteTypes.any { it.page == feature.page } })
        remoteTypes.filter { it.page != RemotePage.REMOTE }.forEach {
            assertEquals(RemotePage.REMOTE_TYPES, it.page.androidBackDestination(RemotePage.REMOTE))
            assertEquals(it.page, RemotePage.DEVICES.androidBackDestination(it.page))
        }
        assertEquals(RemotePage.REMOTE, RemotePage.REMOTE_TYPES.androidBackDestination(RemotePage.REMOTE))
    }
}
