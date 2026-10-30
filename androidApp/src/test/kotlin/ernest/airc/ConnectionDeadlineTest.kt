package ernest.airc

import org.junit.Assert.*
import org.junit.Test

class ConnectionDeadlineTest {
    @Test fun activeSlowPushOutlivesFormerNinetySecondLimitButStalledPushExpires() {
        var time = 0L
        val messages = mutableListOf<String>()
        val deadline = ConnectionDeadline(clock = { time }, report = messages::add)
        deadline.begin(ConnectionStage.PUSH)
        repeat(12) { time += 10_000; deadline.progress((it + 1) * 1024L, 100_000, "推送"); assertNull(deadline.failure(900_000)) }
        assertTrue(messages.last().contains("KB"))
        time += 29_999; assertNull(deadline.failure(900_000))
        time++; assertTrue(deadline.failure(900_000)!!.contains("30 秒无传输进展"))
    }
    @Test fun eachStageGetsItsOwnBudgetAndTheWholeOperationIsStillBounded() {
        var time = 0L
        val deadline = ConnectionDeadline(clock = { time })
        time = 44_000; assertNull(deadline.failure(900_000))
        deadline.begin(ConnectionStage.PUSH)
        time = 65_000; deadline.progress(1024, 2048, "推送"); assertNull(deadline.failure(900_000))
        deadline.begin(ConnectionStage.START)
        time += 99_999; assertNull(deadline.failure(900_000))
        time++; assertTrue(deadline.failure(900_000)!!.contains("阶段时长上限"))
        deadline.begin(ConnectionStage.PUSH)
        time = 900_000; deadline.progress(2048, 2048, "推送")
        assertTrue(deadline.failure(900_000)!!.contains("总时长上限"))
    }
    @Test fun repeatedByteCountsCannotKeepAnIdleTransferAlive() {
        var time = 0L
        val deadline = ConnectionDeadline(clock = { time })
        deadline.begin(ConnectionStage.PUSH); deadline.progress(1, 10, "推送")
        time = 30_000; deadline.progress(1, 10, "推送")
        assertNotNull(deadline.failure(900_000))
    }
    @Test fun streamTracksBothReadFormsAndDoesNotDoubleCount() {
        var time = 0L
        val messages = mutableListOf<String>()
        val deadline = ConnectionDeadline(clock = { time }, report = messages::add)
        deadline.begin(ConnectionStage.PUSH)
        val expected = ByteArray(2048) { it.toByte() }
        val stream = deadline.stream(expected.inputStream(), expected.size.toLong(), "推送")
        assertEquals(0, stream.read()); time = 1000
        assertArrayEquals(expected.drop(1).toByteArray(), stream.readBytes())
        assertTrue(messages.last(), messages.last().contains("2 KB / 2 KB"))
        assertEquals(-1, stream.read())
    }
    @Test fun delayedAdbWaitingIsReportedOncePerStageAndFailureReasonMatchesTheStage() {
        var time = 0L
        val messages = mutableListOf<String>()
        val deadline = ConnectionDeadline(clock = { time }, report = messages::add)
        deadline.begin(ConnectionStage.CONNECT)
        time = 7999; deadline.reportWaiting()
        assertEquals(listOf(ConnectionStage.CONNECT.label), messages)
        time = 8000; deadline.reportWaiting(); deadline.reportWaiting()
        assertEquals("正在等待被控端 ADB 响应…", messages.last())
        assertEquals(2, messages.size)
        assertEquals("被控端 ADB 未响应", deadline.retryReason(timedOut = true))
        assertEquals("被控端 ADB 连接失败", deadline.retryReason())
        deadline.begin(ConnectionStage.RUNTIME)
        time += 8000; deadline.reportWaiting()
        assertEquals(ConnectionStage.RUNTIME.label, messages.last())
        assertEquals("遥控服务未响应", deadline.retryReason(timedOut = true))
        deadline.begin(ConnectionStage.CONNECT)
        time += 8000; deadline.reportWaiting()
        assertEquals("正在等待被控端 ADB 响应…", messages.last())
    }

}
