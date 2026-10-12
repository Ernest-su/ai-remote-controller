package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class RemoteFeedbackTest {
    @Test fun executionProgressRefreshesRevisionAndUsesPhaseFallback() {
        val thinking = RemoteFeedback().updated(FeedbackPhase.THINKING)
        assertEquals("思考中…", thinking.text)
        val tool = thinking.updated(FeedbackPhase.TOOL)
        assertEquals("调用工具中…", tool.text)
        assertTrue(tool.revision > thinking.revision)
        val completed = tool.updated(FeedbackPhase.COMPLETE, "已执行")
        assertEquals("已执行", completed.text)
        assertEquals("网络异常", completed.updated(FeedbackPhase.ERROR, "网络异常").text)
    }
    @Test fun visibilityExpiresFromTheUpdateTimeRatherThanPageEntry() {
        val feedback = RemoteFeedback(text = "已返回", revision = 1, updatedAtNanos = 10_000_000_000L)
        assertEquals(5000L, feedback.remainingVisibleMillis(10_000_000_000L))
        assertEquals(2000L, feedback.remainingVisibleMillis(13_000_000_000L))
        assertEquals(0L, feedback.remainingVisibleMillis(15_000_000_000L))
        assertEquals(0L, feedback.remainingVisibleMillis(60_000_000_000L))
        assertEquals(0L, feedback.copy(text = "").remainingVisibleMillis(10_000_000_000L))
        val refreshed = feedback.updated(FeedbackPhase.TOOL)
        assertEquals(5000L, refreshed.remainingVisibleMillis(refreshed.updatedAtNanos))
    }

    @Test fun removedVoicePhasesAndUnknownEventsAreIgnored() {
        assertNull(progressPhase("recognizing"))
        assertNull(progressPhase("recognized"))
        assertNull(progressPhase("listening"))
        assertNull(progressPhase("unknown"))
        assertEquals(FeedbackPhase.THINKING, progressPhase("thinking"))
        assertEquals(FeedbackPhase.TOOL, progressPhase("tool"))
    }
}
