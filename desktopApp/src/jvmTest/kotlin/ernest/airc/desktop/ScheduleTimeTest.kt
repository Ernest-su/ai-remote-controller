package ernest.airc.desktop

import ernest.airc.nextScheduleOnceTime
import org.junit.Assert.*
import org.junit.Test

class ScheduleTimeTest {
    @Test fun singleTimeUsesNextFutureOccurrenceOnTargetCalendar() {
        assertEquals("2032-02-29 21:00", nextScheduleOnceTime("21:00", "2032-02-29 20:30:00"))
        assertEquals("2032-03-01 20:30", nextScheduleOnceTime("20:30", "2032-02-29 20:30:00"))
        assertEquals("2032-03-01 08:00", nextScheduleOnceTime("08:00", "2032-02-29 20:30:10"))
        assertEquals("2033-01-01 00:00", nextScheduleOnceTime("00:00", "2032-12-31 23:59:50"))
        assertEquals("2033-01-01 23:59", nextScheduleOnceTime("23:59", "2032-12-31 23:58:55", 5))
        assertEquals("2032-03-01 00:00", nextScheduleOnceTime("00:00", "2032-02-29 23:59", 10))
        assertNull(nextScheduleOnceTime("25:00", "2032-02-29 20:30:00"))
        assertNull(nextScheduleOnceTime("20:00", ""))
    }
}
