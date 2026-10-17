import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the application, not by a window that tray mode may dispose. */
internal class AppUpdateController(
    private val scope: CoroutineScope,
    configFile: File,
    private val fetchLatest: () -> Result<GpuMonitorRelease> = UpdateChecker::fetchLatest,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val schedule = UpdateCheckSchedule(configFile.absoluteFile.parentFile.resolve("update-check.timestamp"))
    private val inFlight = AtomicBoolean(false)
    var intervalHours by mutableStateOf(runCatching {
        JsonParser.parseString(configFile.readText()).asJsonObject
            .getAsJsonObject("ui")?.get("update_check_interval_hours")?.asInt
            ?.let(::normalizeUpdateCheckIntervalHours) ?: DefaultUpdateCheckIntervalHours
    }.getOrDefault(DefaultUpdateCheckIntervalHours))
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    var release by mutableStateOf<GpuMonitorRelease?>(null)
    var lastAttemptMillis by mutableStateOf(schedule.lastAttemptMillis())
        private set

    fun setInterval(hours: Int) { intervalHours = normalizeUpdateCheckIntervalHours(hours) }

    fun checkIfDue() {
        if (schedule.isDue(intervalHours, clock())) check(false)
    }

    fun check(manual: Boolean) {
        if (!inFlight.compareAndSet(false, true)) return
        busy = true
        message = "正在检查最新版本…"
        if (manual) release = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    schedule.recordAttempt(clock())
                    fetchLatest()
                }
                lastAttemptMillis = schedule.lastAttemptMillis()
                result.onSuccess { latest ->
                    if (manual) release = latest
                    message = if (latest.updateAvailable) "发现新版 ${latest.tagName}"
                        else "当前已是最新版 · v$GpuMonitorVersion"
                }.onFailure { message = "检查失败，请稍后重试" }
            } finally {
                busy = false
                inFlight.set(false)
            }
        }
    }
}
