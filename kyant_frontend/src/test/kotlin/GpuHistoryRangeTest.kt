import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuHistoryRangeTest {
    @Test
    fun supportsEveryGpuHistoryRangeLabel() {
        assertEquals("15min", gpuHistoryRangeLabel(15))
        assertEquals("1h", gpuHistoryRangeLabel(60))
        assertEquals("6h", gpuHistoryRangeLabel(6 * 60))
        assertEquals("24h", gpuHistoryRangeLabel(24 * 60))
    }

    @Test
    fun recentSamplesStayAtTheRightOfALongWindow() {
        val nowSeconds = 1_800_000_000.0
        val fiveMinutesAgo = nowSeconds - 5 * 60.0

        val fraction = gpuHistoryWindowFraction(
            timestampSeconds = fiveMinutesAgo,
            windowEndSeconds = nowSeconds,
            rangeMinutes = 24 * 60,
        )

        assertTrue(fraction > 0.99f, "Five minutes of data must remain near the right edge of a 24h chart")
        assertEquals(1f, gpuHistoryWindowFraction(nowSeconds, nowSeconds, 24 * 60))
        assertEquals(0f, gpuHistoryWindowFraction(nowSeconds - 24 * 60 * 60.0, nowSeconds, 24 * 60))
    }
}
