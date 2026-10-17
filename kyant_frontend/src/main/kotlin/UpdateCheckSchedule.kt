import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val DefaultUpdateCheckIntervalHours = 24
internal fun normalizeUpdateCheckIntervalHours(hours: Int): Int = hours.coerceIn(12, 168)

/** Persists attempts, including failed requests, so restarts cannot flood GitHub. */
internal class UpdateCheckSchedule(private val stateFile: File) {
    private var lastAttempt = runCatching { stateFile.readText().trim().toLong() }
        .getOrDefault(0L).coerceAtLeast(0L)

    @Synchronized
    fun lastAttemptMillis(): Long = lastAttempt

    @Synchronized
    fun isDue(hours: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val interval = normalizeUpdateCheckIntervalHours(hours) * 3_600_000L
        return lastAttempt == 0L || nowMillis < lastAttempt || nowMillis - lastAttempt >= interval
    }

    @Synchronized
    fun recordAttempt(nowMillis: Long = System.currentTimeMillis()): Result<Unit> {
        lastAttempt = nowMillis.coerceAtLeast(1L)
        // Keep the in-memory deadline even if the local directory is unwritable.
        return runCatching {
            Files.createDirectories(stateFile.absoluteFile.parentFile.toPath())
            val temporary = Files.createTempFile(stateFile.absoluteFile.parentFile.toPath(), "update-check-", ".tmp")
            try {
                Files.writeString(temporary, lastAttempt.toString())
                try {
                    Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
