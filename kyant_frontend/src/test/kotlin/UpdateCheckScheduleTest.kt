import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class UpdateCheckScheduleTest {
    @Test
    fun realPublicReleaseFallbackWhenRequested() {
        if (System.getenv("XERVER_LIVE_UPDATE_CHECK") != "1") return
        val host = System.getProperty("https.proxyHost")
        val port = System.getProperty("https.proxyPort")
        try {
            System.setProperty("https.proxyHost", "127.0.0.1")
            System.setProperty("https.proxyPort", "7890")
            val release = UpdateChecker.fetchLatestRedirect()
            assertTrue(release.tagName.matches(Regex("""v?\d+\.\d+\.\d+""")))
            assertTrue(release.pageUrl.startsWith("$GpuMonitorReleasesUrl/tag/"))
        } finally {
            if (host == null) System.clearProperty("https.proxyHost") else System.setProperty("https.proxyHost", host)
            if (port == null) System.clearProperty("https.proxyPort") else System.setProperty("https.proxyPort", port)
        }
    }

    @Test
    fun intervalBoundsAndDefaultAreSupported() {
        assertEquals(24, UiSettings().updateCheckIntervalHours)
        assertEquals(12, normalizeUpdateCheckIntervalHours(1))
        assertEquals(168, normalizeUpdateCheckIntervalHours(500))
        assertEquals(36, normalizeUpdateCheckIntervalHours(36))
    }

    @Test
    fun attemptsPersistAcrossRestartsAndDoNotRetryBeforeTheInterval() {
        val directory = Files.createTempDirectory("xerver-update-schedule").toFile()
        try {
            val file = directory.resolve("update.timestamp")
            val now = 1_800_000_000_000L
            val schedule = UpdateCheckSchedule(file)
            assertTrue(schedule.isDue(24, now))
            schedule.recordAttempt(now).getOrThrow()
            val restarted = UpdateCheckSchedule(file)
            assertFalse(restarted.isDue(12, now + 12 * 3_600_000L - 1))
            assertTrue(restarted.isDue(12, now + 12 * 3_600_000L))
            assertFalse(restarted.isDue(168, now + 167 * 3_600_000L))
            assertTrue(restarted.isDue(168, now + 168 * 3_600_000L))
            // Manual checks bypass isDue and update the next automatic deadline.
            restarted.recordAttempt(now + 1000).getOrThrow()
            assertEquals(now + 1000, UpdateCheckSchedule(file).lastAttemptMillis())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun failedPersistenceStillLimitsRequestsInThisSessionAndClockRollbackRecovers() {
        val directory = Files.createTempDirectory("xerver-update-failure").toFile()
        try {
            val blocked = directory.resolve("blocked").apply { writeText("file") }
            val schedule = UpdateCheckSchedule(blocked.resolve("state"))
            assertTrue(schedule.recordAttempt(10000).isFailure)
            assertFalse(schedule.isDue(12, 11000))
            assertTrue(schedule.isDue(12, 9000))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun onlyFailedPrimaryRequestsUseThePublicPageFallbackOnce() {
        var calls = 0
        val release = UpdateChecker.releaseFromLatestLocation("$GpuMonitorReleasesUrl/tag/v99.0.0")
        val result = UpdateChecker.fetchLatestUsing({ calls++; error("HTTP 403") }, { calls++; release })
        assertEquals(release, result.getOrThrow())
        assertEquals(2, calls)
        UpdateChecker.fetchLatestUsing({ release }, { error("fallback should not run") }).getOrThrow()
    }

    @Test
    fun fallbackAcceptsOnlyStableVersionsFromThisRepository() {
        assertTrue(UpdateChecker.releaseFromLatestLocation("/byebabyblue/gpu-monitor/releases/tag/v99.0.0").updateAvailable)
        assertFailsWith<IllegalArgumentException> { UpdateChecker.releaseFromLatestLocation("https://evil.example/releases/tag/v99.0.0") }
        assertFailsWith<IllegalArgumentException> { UpdateChecker.releaseFromLatestLocation("https://github.com/other/project/releases/tag/v99.0.0") }
        assertFailsWith<IllegalArgumentException> { UpdateChecker.releaseFromLatestLocation("$GpuMonitorReleasesUrl/tag/v99.0.0-beta") }
    }
}
