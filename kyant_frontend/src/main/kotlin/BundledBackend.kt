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
    private const val AppDirectoryName = "GPU Monitor"

    fun configFile(): File {
        System.getenv("GPU_MONITOR_CONFIG_PATH")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it).absoluteFile }
        val roaming = System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Roaming")
        return File(File(roaming, AppDirectoryName), "config.json")
    }

    fun configuredApiPort(): Int? {
        val config = configFile()
        if (!config.isFile) return null
        return runCatching {
            val root = JsonParser.parseReader(config.reader(StandardCharsets.UTF_8)).asJsonObject
            root.getAsJsonObject("settings")?.get("api_port")?.asInt
        }.getOrNull()?.takeIf { it in 1024..65535 }
    }

    fun ensureConfigFile(): File {
        val config = configFile()
        if (config.isFile) return config
        Files.createDirectories(config.parentFile.toPath())
        val root = JsonObject().apply {
            add("settings", JsonObject().apply {
                addProperty("gpu_active_interval", 3)
                addProperty("gpu_idle_interval", 60)
                addProperty("disk_interval", 30)
                addProperty("process_interval", 6)
                addProperty("idle_threshold", 600)
                addProperty("api_port", DefaultApiPort)
            })
            add("ui", JsonObject().apply {
                addProperty("wallpaper_path", "")
                addProperty("default_page", "home")
                addProperty("autostart", false)
                addProperty("readability_blur", false)
                addProperty("text_mode", "light")
                addProperty("top_bar_blur", true)
                addProperty("bottom_bar_blur", true)
                addProperty("font_scale", 1.08)
                add("hidden_servers", com.google.gson.JsonArray())
                addProperty("remember_window_bounds", false)
            })
            add("servers", com.google.gson.JsonArray())
        }
        Files.writeString(config.toPath(), root.toString(), StandardCharsets.UTF_8)
        return config
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

internal class BundledBackendController {
    @Volatile
    private var process: Process? = null

    @Synchronized
    fun startIfAvailable() {
        if (process?.isAlive == true) return
        val port = MonitorClient.initialPort()
        if (isPortOpen(port)) return

        val resources = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: return
        val backend = File(resources, "backend/gpu-monitor-backend.exe")
        if (!backend.isFile) return

        val config = MonitorInstallation.ensureConfigFile()
        val builder = ProcessBuilder(
            backend.absolutePath,
            "--backend-only",
            "--api-port",
            port.toString(),
            "--config",
            config.absolutePath,
        )
            .directory(config.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)

        packagedFrontendPath()?.let { builder.environment()["GPU_MONITOR_FRONTEND_EXE"] = it }
        process = builder.start()
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
