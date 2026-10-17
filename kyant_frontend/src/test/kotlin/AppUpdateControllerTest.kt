import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdateControllerTest {
    @Test
    fun applicationControllerKeepsOneRequestAndDeadlineAcrossWindowChangesAndRestarts() = runBlocking {
        val directory = Files.createTempDirectory("xerver-application-update").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            val config = directory.resolve("config.json").apply {
                writeText("""{"ui":{"update_check_interval_hours":168}}""")
            }
            val now = AtomicLong(1_800_000_000_000L)
            val calls = AtomicInteger()
            val release = UpdateChecker.releaseFromLatestLocation("$GpuMonitorReleasesUrl/tag/v99.0.0")
            val controller = AppUpdateController(scope, config, {
                calls.incrementAndGet()
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                Result.success(release)
            }, now::get)
            assertEquals(168, controller.intervalHours)
            controller.checkIfDue()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // Recreated windows continue to use this same application controller.
            controller.check(true)
            controller.checkIfDue()
            assertEquals(1, calls.get())
            finish.countDown()
            withTimeout(5000) { while (controller.busy) delay(10) }
            now.addAndGet(12 * 3_600_000L)
            controller.checkIfDue()
            assertEquals(1, calls.get())
            controller.setInterval(12)
            controller.checkIfDue()
            withTimeout(5000) { while (controller.busy) delay(10) }
            assertEquals(2, calls.get())
            val restarted = AppUpdateController(scope, config, { error("deadline should persist") }, now::get)
            restarted.checkIfDue()
            assertEquals("", restarted.message)
        } finally {
            finish.countDown()
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
