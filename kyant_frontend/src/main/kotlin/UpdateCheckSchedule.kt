import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores the last manual check time for display. No automatic update schedule. */
internal class UpdateCheckSchedule(private val stateFile: File) {
    private var lastAttempt = runCatching { stateFile.readText().trim().toLong() }
        .getOrDefault(0L).coerceAtLeast(0L)

    @Synchronized
    fun lastAttemptMillis(): Long = lastAttempt

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
