import java.awt.Desktop
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Local-only diagnostics: never record configuration contents or request bodies. */
internal object StartupDiagnostics {
    private const val MaxLogBytes = 4L * 1024L * 1024L
    private val writeLock = Any()

    fun logDirectory(): File = File(MonitorInstallation.configFile().parentFile, "logs")

    private fun fallbackDirectory(): File = File(System.getProperty("java.io.tmpdir"), "Xerver/logs")

    fun installUncaughtExceptionLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            logException("未捕获异常 thread=${thread.name}", error)
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
    }

    fun logStartupSnapshot() {
        log(
            "前端进程启动 version=$GpuMonitorVersion pid=${ProcessHandle.current().pid()} " +
                "java=${System.getProperty("java.version")} javaHome=${System.getProperty("java.home")} " +
                "os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}",
        )
        log(
            "config=${MonitorInstallation.configFile().absolutePath} " +
                "resources=${System.getProperty("compose.application.resources.dir")} " +
                "launcher=${System.getProperty("jpackage.app-path")}",
        )
    }

    fun log(message: String) {
        // Logging must never turn a recoverable startup error into a UI crash.
        runCatching {
            synchronized(writeLock) {
                val line = "${Instant.now()} [frontend] ${message.trimEnd()}\n"
                if (runCatching { append(File(logDirectory(), "frontend.log"), line) }.isFailure) {
                    append(File(fallbackDirectory(), "frontend.log"), line)
                }
            }
        }
    }

    fun logException(context: String, error: Throwable) {
        log("$context\n${error.stackTraceToString()}")
    }

    fun prepareBackendLog(): File = synchronized(writeLock) {
        val primary = File(logDirectory(), "backend.log")
        runCatching { prepare(primary) }.getOrElse {
            prepare(File(fallbackDirectory(), "backend.log"))
        }
    }

    fun openLogDirectory(): Result<File> = runCatching {
        val directory = runCatching {
            Files.createDirectories(logDirectory().toPath()).toFile()
        }.getOrElse {
            Files.createDirectories(fallbackDirectory().toPath()).toFile()
        }
        Desktop.getDesktop().open(directory)
        directory
    }

    private fun append(file: File, line: String) {
        prepare(file)
        Files.writeString(
            file.toPath(), line, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    private fun prepare(file: File): File {
        Files.createDirectories(file.parentFile.toPath())
        if (file.isFile && file.length() > MaxLogBytes) {
            // Keep the previous log for diagnosis instead of deleting all history.
            Files.move(
                file.toPath(), File(file.parentFile, "${file.nameWithoutExtension}.previous.log").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        // Validate writability before passing a log to ProcessBuilder.
        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { }
        return file
    }
}
