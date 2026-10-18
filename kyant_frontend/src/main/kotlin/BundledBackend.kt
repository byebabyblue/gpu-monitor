import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal data class MonitorWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal object MonitorInstallation {
    private const val AppDirectoryName = "Xerver"
    private const val LegacyAppDirectoryName = "GPU Monitor"
    private const val ConfigFileName = "config.json"

    fun configFile(): File {
        System.getenv("GPU_MONITOR_CONFIG_PATH")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it).absoluteFile }
        return File(File(roamingRoot(), AppDirectoryName), ConfigFileName)
    }

    private fun roamingRoot(): File = System.getProperty("xerver.appdata.root")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Roaming")

    private fun legacyConfigFile(): File? {
        if (!System.getenv("GPU_MONITOR_CONFIG_PATH").isNullOrBlank()) return null
        return File(File(roamingRoot(), LegacyAppDirectoryName), ConfigFileName)
    }

    fun configuredApiPort(): Int? {
        val candidates = listOfNotNull(configFile(), legacyConfigFile()).distinctBy { it.absolutePath }
        for (config in candidates) {
            if (!config.isFile) continue
            val port = runCatching {
                val root = JsonParser.parseReader(config.reader(StandardCharsets.UTF_8)).asJsonObject
                root.getAsJsonObject("settings")?.get("api_port")?.asInt
            }.getOrNull()?.takeIf { it in 1024..65535 }
            if (port != null) return port
        }
        return null
    }

    fun startMinimizedToTray(): Boolean {
        val candidates = listOfNotNull(configFile(), legacyConfigFile()).distinctBy { it.absolutePath }
        for (config in candidates) {
            if (!config.isFile) continue
            val enabled = runCatching {
                val root = JsonParser.parseReader(config.reader(StandardCharsets.UTF_8)).asJsonObject
                root.getAsJsonObject("ui")
                    ?.get("start_minimized_to_tray")
                    ?.asBoolean
            }.getOrNull()
            if (enabled != null) return enabled
        }
        return DefaultStartMinimizedToTray
    }

    fun ensureConfigFile(): File {
        val config = configFile()
        if (config.isFile) return config
        Files.createDirectories(config.parentFile.toPath())
        val backup = File(config.parentFile, "config.backup.json")
        if (backup.isFile) {
            Files.copy(backup.toPath(), config.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return config
        }
        val legacyConfig = legacyConfigFile()
        if (legacyConfig?.isFile == true) {
            Files.copy(legacyConfig.toPath(), config.toPath(), StandardCopyOption.REPLACE_EXISTING)
            migrateLegacyCompanionFiles(legacyConfig.parentFile, config.parentFile)
            return config
        }
        val root = JsonObject().apply {
            add("settings", JsonObject().apply {
                addProperty("gpu_active_interval", 3)
                addProperty("gpu_idle_interval", 60)
                addProperty("disk_interval", 21600)
                addProperty("process_interval", 6)
                addProperty("idle_threshold", 600)
                addProperty("api_port", DefaultApiPort)
            })
            add("ui", JsonObject().apply {
                addProperty("wallpaper_path", "")
                addProperty("default_page", "home")
                addProperty("autostart", false)
                addProperty("start_minimized_to_tray", DefaultStartMinimizedToTray)
                addProperty("card_blur_level", BlurLevelOff)
                addProperty("text_mode", "system")
                addProperty("top_bar_blur_level", BlurLevelMedium)
                addProperty("bottom_bar_blur_level", BlurLevelMedium)
                addProperty("glass_tint", "clear")
                addProperty("font_scale", 1.35)
                add("hidden_servers", com.google.gson.JsonArray())
                addProperty("remember_window_bounds", false)
                addProperty("tray_lightweight_enabled", false)
                addProperty("tray_lightweight_delay_minutes", DefaultTrayLightweightDelayMinutes)
                addProperty("gpu_history_range_minutes", DefaultGpuHistoryRangeMinutes)
            })
            add("servers", com.google.gson.JsonArray())
        }
        Files.writeString(config.toPath(), root.toString(), StandardCharsets.UTF_8)
        return config
    }

    private fun migrateLegacyCompanionFiles(legacyDirectory: File, targetDirectory: File) {
        listOf("config.backup.json", "ssh_known_hosts").forEach { name ->
            val source = File(legacyDirectory, name)
            val target = File(targetDirectory, name)
            if (source.isFile && !target.exists()) {
                Files.copy(source.toPath(), target.toPath())
            }
        }
    }

    fun launchWindowBounds(): MonitorWindowBounds {
        val workAreas = availableWorkAreas()
        val primary = workAreas.first()
        val stored = readWindowPreferences()
            ?.takeIf { it.first }
            ?.second
        val targetArea = stored?.let { saved ->
            val savedRectangle = Rectangle(saved.x, saved.y, saved.width, saved.height)
            workAreas.maxByOrNull { area -> intersectionArea(area, savedRectangle) }
                ?.takeIf { intersectionArea(it, savedRectangle) > 0L }
        } ?: primary

        val desired = stored ?: MonitorWindowBounds(
            x = targetArea.x + (targetArea.width - minOf(1320, (targetArea.width - 32).coerceAtLeast(1))) / 2,
            y = targetArea.y + (targetArea.height - minOf(860, (targetArea.height - 32).coerceAtLeast(1))) / 2,
            width = minOf(1320, (targetArea.width - 32).coerceAtLeast(1)),
            height = minOf(860, (targetArea.height - 32).coerceAtLeast(1)),
        )
        return clampToWorkArea(desired, targetArea)
    }

    fun persistWindowBoundsIfEnabled(bounds: Rectangle): Boolean {
        val config = configFile()
        if (!config.isFile) return false
        return runCatching {
            val root = JsonParser.parseReader(config.reader(StandardCharsets.UTF_8)).asJsonObject
            val ui = root.getAsJsonObject("ui") ?: JsonObject().also { root.add("ui", it) }
            if (!ui.get("remember_window_bounds")?.asBoolean.orFalse()) return false

            val safe = clampToWorkArea(
                MonitorWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                bestWorkAreaFor(bounds),
            )
            ui.add("window_bounds", JsonObject().apply {
                addProperty("x", safe.x)
                addProperty("y", safe.y)
                addProperty("width", safe.width)
                addProperty("height", safe.height)
            })

            Files.createDirectories(config.parentFile.toPath())
            val temp = File(config.parentFile, "${config.name}.window.tmp")
            Files.writeString(
                temp.toPath(),
                GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
                StandardCharsets.UTF_8,
            )
            runCatching {
                Files.move(
                    temp.toPath(),
                    config.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temp.toPath(), config.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrDefault(false)
    }

    private fun readWindowPreferences(): Pair<Boolean, MonitorWindowBounds?>? {
        val config = configFile()
        if (!config.isFile) return null
        return runCatching {
            val root = JsonParser.parseReader(config.reader(StandardCharsets.UTF_8)).asJsonObject
            val ui = root.getAsJsonObject("ui") ?: JsonObject()
            val enabled = ui.get("remember_window_bounds")?.asBoolean.orFalse()
            val saved = ui.getAsJsonObject("window_bounds")?.let { value ->
                MonitorWindowBounds(
                    x = value.get("x")?.asInt ?: return@let null,
                    y = value.get("y")?.asInt ?: return@let null,
                    width = value.get("width")?.asInt ?: return@let null,
                    height = value.get("height")?.asInt ?: return@let null,
                ).takeIf { it.width >= 320 && it.height >= 240 }
            }
            enabled to saved
        }.getOrNull()
    }

    private fun availableWorkAreas(): List<Rectangle> = runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            val insets = toolkit.getScreenInsets(configuration)
            Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                (bounds.width - insets.left - insets.right).coerceAtLeast(1),
                (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
            )
        }
    }.getOrElse {
        listOf(Rectangle(0, 0, 1280, 720))
    }.ifEmpty { listOf(Rectangle(0, 0, 1280, 720)) }

    private fun bestWorkAreaFor(bounds: Rectangle): Rectangle {
        val workAreas = availableWorkAreas()
        return workAreas.maxByOrNull { intersectionArea(it, bounds) }
            ?.takeIf { intersectionArea(it, bounds) > 0L }
            ?: workAreas.first()
    }

    private fun clampToWorkArea(bounds: MonitorWindowBounds, area: Rectangle): MonitorWindowBounds {
        val minimumWidth = minOf(760, area.width)
        val minimumHeight = minOf(560, area.height)
        val width = bounds.width.coerceIn(minimumWidth, area.width)
        val height = bounds.height.coerceIn(minimumHeight, area.height)
        val maxX = area.x + area.width - width
        val maxY = area.y + area.height - height
        return MonitorWindowBounds(
            x = bounds.x.coerceIn(area.x, maxX),
            y = bounds.y.coerceIn(area.y, maxY),
            width = width,
            height = height,
        )
    }

    private fun intersectionArea(first: Rectangle, second: Rectangle): Long {
        val intersection = first.intersection(second)
        return if (intersection.width <= 0 || intersection.height <= 0) 0L
        else intersection.width.toLong() * intersection.height.toLong()
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false
}

internal class BundledBackendController(
    private val portProvider: () -> Int = MonitorClient::initialPort,
    private val launchProcess: (ProcessBuilder) -> Process = { it.start() },
) {
    @Volatile
    private var process: Process? = null

    @Synchronized
    fun startIfAvailable() {
        if (process?.isAlive == true) return
        // Configuration, log setup and ProcessBuilder validation can all throw
        // before start(). Keep every startup stage inside the recovery boundary.
        runCatching {
            val port = portProvider()
            StartupDiagnostics.log("内置后端启动检查 port=$port")
            if (isPortOpen(port)) {
                StartupDiagnostics.log("本地端口已打开，不重复启动后端 port=$port")
                return
            }

            val resources = System.getProperty("compose.application.resources.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            checkNotNull(resources) { "未找到应用资源目录，无法启动内置后端" }
            val backend = File(resources, "backend/gpu-monitor-backend.exe")
            check(backend.isFile) { "未找到内置后端：${backend.absolutePath}" }

            val config = MonitorInstallation.ensureConfigFile()
            val logFile = StartupDiagnostics.prepareBackendLog()
            StartupDiagnostics.log(
                "准备启动内置后端 path=${backend.absolutePath} bytes=${backend.length()} " +
                    "config=${config.absolutePath} backendLog=${logFile.absolutePath}",
            )
            val builder = ProcessBuilder(
                backend.absolutePath,
                "--backend-only",
                "--api-port",
                port.toString(),
                "--config",
                config.absolutePath,
            )
                .directory(config.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            builder.environment()["PYTHONUNBUFFERED"] = "1"
            packagedFrontendPath()?.let { builder.environment()["GPU_MONITOR_FRONTEND_EXE"] = it }

            val launched = launchProcess(builder)
            process = launched
            // DISCARD is output-only. The default stdin pipe must instead be
            // closed after launch so the non-interactive backend receives EOF.
            runCatching { launched.outputStream.close() }.onFailure {
                StartupDiagnostics.logException("关闭后端标准输入失败", it)
            }
            StartupDiagnostics.log("已启动内置后端 pid=${launched.pid()} port=$port")
            launched.onExit().thenAccept { exited ->
                if (process === launched) process = null
                StartupDiagnostics.log(
                    "内置后端已退出 code=${runCatching { exited.exitValue() }.getOrDefault(-1)}",
                )
            }
        }.onFailure { error ->
            StartupDiagnostics.logException("启动内置后端失败，界面仍可使用并重试", error)
        }
    }

    @Synchronized
    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.toHandle().descendants().forEach { it.destroy() }
            running.destroy()
            if (!running.waitFor(1500, TimeUnit.MILLISECONDS)) {
                running.toHandle().descendants().forEach { it.destroyForcibly() }
                running.destroyForcibly()
            }
        }
    }

    private fun packagedFrontendPath(): String? {
        val packaged = System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() && File(it).isFile }
        if (packaged != null) return File(packaged).absolutePath
        return ProcessHandle.current().info().command().orElse("")
            .takeIf { it.isNotBlank() && File(it).isFile }
            ?.let { File(it).absolutePath }
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 250) }
        true
    }.getOrDefault(false)
}
