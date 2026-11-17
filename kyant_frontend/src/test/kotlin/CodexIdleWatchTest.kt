import java.io.File
import kotlin.test.*

class CodexIdleWatchTest {
    private val first = IdleCard("p100", 0)
    private val other = IdleCard("p100", 1)
    private fun sample(t: Long, card: IdleCard = first, utilization: Double = 0.0, used: Double = 2.0, queued: Int = 0) =
        IdleSample(card, t, utilization, used, queued)

    @Test fun continuousSelectedIdleAlertsOnceAndOnlyRearmsAfterBusy() {
        val tracker = IdleTracker(IdleRule(cards = listOf(first)))
        for (t in listOf(0L, 15L, 30L, 45L)) assertTrue(tracker.observe(listOf(sample(t), sample(t, other))).isEmpty())
        assertEquals(listOf(first), tracker.observe(listOf(sample(60), sample(60, other))).map { it.card })
        assertTrue(tracker.observe(listOf(sample(75))).isEmpty())
        tracker.observe(emptyList())
        assertTrue(tracker.observe(listOf(sample(200))).isEmpty())
        assertTrue(tracker.observe(listOf(sample(260))).isEmpty())
        tracker.observe(listOf(sample(270, used = 1024.0)))
        assertTrue(tracker.observe(listOf(sample(280))).isEmpty())
        assertEquals(1, tracker.observe(listOf(sample(340))).size)
    }

    @Test fun staleGapDuplicateTimestampAndQueueDoNotQualify() {
        val tracker = IdleTracker(IdleRule(cards = listOf(first)))
        repeat(10) { assertTrue(tracker.observe(listOf(sample(100))).isEmpty()) }
        assertTrue(tracker.observe(listOf(sample(250))).isEmpty())
        assertTrue(tracker.observe(listOf(sample(310, queued = 1))).isEmpty())
        assertTrue(tracker.observe(listOf(sample(320))).isEmpty())
        assertEquals(1, tracker.observe(listOf(sample(380))).size)
    }

    @Test fun customMessageAndMetricsUseStdinForTheExactSession() {
        val text = "安排实验 A，先给计划"
        val session = "12345678-1234-1234-1234-123456789000"
        val command = codexIdleCommand(File("codex.exe"), session)
        assertEquals(listOf(session, "-"), command.takeLast(2))
        assertContains(command, "read-only")
        assertFalse(text in command)
        assertTrue(codexIdlePrompt(text, listOf(sample(0))).startsWith(text))
        assertContains(codexIdlePrompt(text, listOf(sample(0))), "p100")
    }

    @Test fun anySelectedCardCanTriggerWhileAnotherIsBusy() {
        val tracker = IdleTracker(IdleRule(cards = listOf(first, other)))
        assertTrue(tracker.observe(listOf(sample(0), sample(0, other, utilization = 99.0))).isEmpty())
        assertEquals(listOf(first), tracker.observe(listOf(sample(60), sample(60, other, utilization = 99.0))).map { it.card })
    }

    @Test fun deliveryFailureNotesDoNotExposeRawCliOutput() {
        assertEquals("本机 CLI 找不到这条对话", codexFailureHint("ERROR: No rollout found for arbitrary UUID"))
        assertEquals("Codex 达到使用额度或请求限额", codexFailureHint("HTTP 429 with local paths"))
        assertNull(codexFailureHint("Unclassified error with private data"))
    }

    @Test fun realCliDeliveryToExplicitDiagnosticSession() = kotlinx.coroutines.runBlocking {
        val session = System.getenv("XERVER_TEST_CODEX_THREAD") ?: return@runBlocking
        val executable = System.getenv("XERVER_TEST_CODEX_EXE") ?: error("No CLI executable for diagnostic")
        val prompt = codexIdlePrompt("Xerver 独立联调：只回复 OK，不调用工具。", listOf(sample(60)))
        assertNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sendCodexIdleMessage(File(executable), session, prompt)
        })
    }
}
