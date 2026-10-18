import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuHistoryRangeTest {
    @Test
    fun defaultsToTheFullDayAndNormalizesStoredValues() {
        assertEquals(24 * 60, UiSettings().gpuHistoryRangeMinutes)
        assertEquals(listOf(15, 60, 360, 1440), GpuHistoryRangeOptions)
        assertEquals(15, normalizeGpuHistoryRangeMinutes(20))
        assertEquals(60, normalizeGpuHistoryRangeMinutes(58))
        assertEquals(360, normalizeGpuHistoryRangeMinutes(400))
        assertEquals(1440, normalizeGpuHistoryRangeMinutes(1200))
    }

    @Test
    fun lastUpdateIsDisplayedOnlyToSeconds() {
        assertEquals("2026-08-23 13:27:42", formatLastUpdate("2026-08-23 13:27:42.987654"))
        assertEquals("2026-08-23 13:27:42", formatLastUpdate("2026-08-23T13:27:42+08:00"))
        assertEquals("刚刚", formatLastUpdate(""))
    }

    @Test
    fun supportsEveryGpuHistoryRangeLabel() {
        assertEquals("15分钟", gpuHistoryRangeLabel(15))
        assertEquals("1小时", gpuHistoryRangeLabel(60))
        assertEquals("6小时", gpuHistoryRangeLabel(6 * 60))
        assertEquals("24小时", gpuHistoryRangeLabel(24 * 60))
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
