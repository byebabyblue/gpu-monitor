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
    fun manualCheckTimesPersistAcrossRestarts() {
        val directory = Files.createTempDirectory("xerver-update-state").toFile()
        try {
            val file = directory.resolve("update.timestamp")
            val state = UpdateCheckSchedule(file)
            assertEquals(0L, state.lastAttemptMillis())
            state.recordAttempt(10000L).getOrThrow()
            assertEquals(10000L, UpdateCheckSchedule(file).lastAttemptMillis())
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
