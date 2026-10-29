package ernest.airc

import kotlin.test.Test
import kotlin.test.assertEquals

class ManagementSizeTest {
    @Test fun sizesUseReadableUnitsWithoutChangingThe1024Base() {
        assertEquals("大小未知", managementFileSize(null))
        assertEquals("0 B", managementFileSize(0))
        assertEquals("1023 B", managementFileSize(1023))
        assertEquals("1 KB", managementFileSize(1024))
        assertEquals("1.5 KB", managementFileSize(1536))
        assertEquals("120.6 KB", managementFileSize(123456))
        assertEquals("1 MB", managementFileSize(1024L * 1024))
        assertEquals("1.5 GB", managementFileSize(1536L * 1024 * 1024))
        assertEquals("1 TB", managementFileSize(1024L * 1024 * 1024 * 1024))
    }

    @Test fun byteProgressHandlesUnknownTotalsAndKeepsItemCounts() {
        assertEquals("512 KB / 2 MB", managementTransferProgress(512L * 1024, 2L * 1024 * 1024))
        assertEquals("1 GB", managementTransferProgress(1024L * 1024 * 1024, null))
        assertEquals("0 B / 0 B", managementTransferProgress(0, 0))
        assertEquals("3 / 10 项", managementTransferProgress(3, 10, "items"))
        assertEquals("3 项", managementTransferProgress(3, null, "items"))
    }
}
