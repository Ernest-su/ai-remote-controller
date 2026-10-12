package ernest.airc

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStatusTest {
    @Test fun structuredStatusSelectsActivePlaybackAndLeavesInactiveSessionsIdle() {
        assertEquals("正在播放 · 曲目", structuredMediaStatusLabel("""{"sessions":[{"packageName":"old","active":false,"state":3},{"packageName":"player","active":true,"state":3,"description":"曲目","positionMs":123}]}"""))
        assertEquals("暂无媒体播放", structuredMediaStatusLabel("""{"sessions":[{"active":false,"state":7}]}"""))
    }

    @Test fun namedStateAndPermissionFailureAreHandled() {
        assertEquals("正在播放", mediaStatusLabel("state=PlaybackState {state=PLAYING(3), position=0}"))
        assertEquals("无权限读取播放状态", mediaStatusLabel("Permission Denial: can't dump MediaSessionService"))
    }
    @Test fun absentSessionDoesNotPretendToBePlaying() {
        assertEquals("暂无媒体播放", mediaStatusLabel("0 sessions"))
    }
    @Test fun activePlaybackTakesPriorityOverPausedSession() {
        val dump = """
            Old tag=old
              state=PlaybackState {state=2, position=12}
              metadata: size=1, description=Old song
            Player tag=player
              state=PlaybackState {state=3, position=123}
              metadata: size=2, description=Song, Artist
        """.trimIndent()
        assertEquals("正在播放 · Song, Artist", mediaStatusLabel(dump))
    }
    @Test fun pausedSessionWithoutMetadataStillReportsPause() {
        assertEquals("已暂停", mediaStatusLabel("state=PlaybackState {state=2, position=0}"))
    }
}
