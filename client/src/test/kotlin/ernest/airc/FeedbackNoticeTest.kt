package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class FeedbackNoticeTest {
    @Test fun completeIncludingConnectionResetNeverShowsGenericNotice() {
        val feedback = RemoteFeedback().copy(revision = 1)
        assertNull(feedback.snackbarMessage(0))
        assertNull(feedback.updated(FeedbackPhase.COMPLETE, "已连接目标").snackbarMessage(0))
    }
    @Test fun inlineErrorsDoNotRepeatInSnackbar() {
        val error = RemoteFeedback().updated(FeedbackPhase.ERROR, "自动部署失败")
        assertNull(error.snackbarMessage(0, "自动部署失败"))
    }
    @Test fun oldErrorsDoNotReplayOnRecreation() {
        val error = RemoteFeedback().updated(FeedbackPhase.ERROR, "连接失败")
        assertNull(error.snackbarMessage(error.revision))
    }
    @Test fun newErrorsWithoutInlineMessageRemainVisible() {
        val error = RemoteFeedback().updated(FeedbackPhase.ERROR, "打开应用失败")
        assertEquals("打开应用失败", error.snackbarMessage(0, "正在连接"))
        assertEquals("打开应用失败", error.snackbarMessage(0))
    }
}
