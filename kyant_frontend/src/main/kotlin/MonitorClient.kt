import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.sin

internal const val DefaultApiPort = 8766

internal data class MonitorGpu(
    val index: Int,
    val name: String,
    val utilization: Double,
    val memoryPercent: Double,
    val memoryUsedMib: Double,
    val memoryTotalMib: Double,
    val temperatureC: Double,
    val powerWatts: Double,
)

internal data class MonitorDisk(
    val path: String,
    val filesystem: String,
    val mount: String,
    val totalGb: Double,
    val usedGb: Double,
    val percent: Double,
) {
    val availableGb: Double get() = (totalGb - usedGb).coerceAtLeast(0.0)
}

internal data class MonitorTask(
    val pid: String,
    val user: String,
    val cpuPercent: Double,
    val memoryPercent: Double,
    val gpu: String,
    val state: String,
    val command: String,
    val elapsed: String,
    val note: String,
)

internal data class MonitorServer(
    val name: String,
    val status: String,
    val connected: Boolean,
    val error: String,
    val cpuPercent: Double,
    val cpuCores: Int,
    val memoryPercent: Double,
    val memoryUsedGb: Double,
    val memoryTotalGb: Double,
    val diskPercent: Double,
    val gpus: List<MonitorGpu>,
    val runningTasks: Int,
    val queuedTasks: Int,
    val lastUpdate: String,
    val disks: List<MonitorDisk> = emptyList(),
    val runningCommands: List<MonitorTask> = emptyList(),
    val queuedCommands: List<MonitorTask> = emptyList(),
) {
    val isShowcase: Boolean get() = status == "showcase"
    val averageGpuPercent: Double
        get() = if (gpus.isEmpty()) 0.0 else gpus.map { it.utilization }.average()
}

internal data class HostKeyPrompt(
    val serverName: String,
    val host: String,
    val port: Int,
    val user: String,
    val keyType: String,
    val fingerprint: String,
)

internal data class HistoryPoint(
    val timestampSeconds: Double,
    val value: Double,
)

internal data class GpuHistorySeries(
    val index: Int,
    val name: String,
    val points: List<HistoryPoint>,
)

internal data class PollSettings(
    val gpuActiveSeconds: Int = 3,
    val gpuIdleSeconds: Int = 60,
    val diskSeconds: Int = 30,
    val processSeconds: Int = 6,
    val idleThresholdSeconds: Int = 600,
    val apiPort: Int = DefaultApiPort,
)

internal data class UiSettings(
    val wallpaperPath: String = "",
    val defaultPage: String = "home",
    val autostart: Boolean = false,
    val readabilityBlur: Boolean = false,
    val readabilityShade: Boolean = false,
    val textMode: String = "light",
    val topBarBlur: Boolean = true,
    val bottomBarBlur: Boolean = true,
    val glassTint: String = "clear",
    val fontScale: Double = 1.08,
    val hiddenServers: List<String> = emptyList(),
    val rememberWindowBounds: Boolean = false,
    val windowBounds: MonitorWindowBounds? = null,
)

internal data class DashboardConfig(
    val polling: PollSettings = PollSettings(),
    val ui: UiSettings = UiSettings(),
)

internal data class ServerDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "root",
    val password: String = "",
    val keyFile: String = "",
    val diskPath: String = ".",
)

internal data class SaveResult(
    val config: DashboardConfig,
    val restartRequired: Boolean,
)

internal object MonitorClient {
    fun initialPort(): Int = System.getenv("GPU_MONITOR_API_PORT")
        ?.toIntOrNull()
        ?.takeIf { it in 1024..65535 }
        ?: MonitorInstallation.configuredApiPort()
        ?: DefaultApiPort

    fun fetchServers(port: Int): Result<List<MonitorServer>> = runCatching {
        val root = request(port, "GET", "/api/servers").asJsonObject
        root.entrySet()
            .map { (name, value) -> parseServer(name, value.asJsonObject) }
            .sortedBy { it.name.lowercase() }
    }

    fun fetchHistory(port: Int, serverName: String): Result<List<GpuHistorySeries>> = runCatching {
        val encoded = URLEncoder.encode(serverName, StandardCharsets.UTF_8)
            .replace("+", "%20")
        val root = request(port, "GET", "/api/servers/$encoded/history").asJsonObject
        root.arrayValue("series").map { element ->
            val series = element.asJsonObject
            GpuHistorySeries(
                index = series.intValue("index"),
                name = series.stringValue("name"),
                points = series.arrayValue("points").map { pointElement ->
                    val point = pointElement.asJsonObject
                    HistoryPoint(
                        timestampSeconds = point.doubleValue("timestamp"),
                        value = point.doubleValue("value"),
                    )
                },
            )
        }
    }

    fun fetchConfig(port: Int): Result<DashboardConfig> = runCatching {
        parseConfig(request(port, "GET", "/api/config").asJsonObject)
    }

    fun fetchHostKeyPrompts(port: Int): Result<List<HostKeyPrompt>> = runCatching {
        request(port, "GET", "/api/host-key-prompts").asJsonObject
            .arrayValue("prompts")
            .map { element ->
                val prompt = element.asJsonObject
                HostKeyPrompt(
                    serverName = prompt.stringValue("server_name"),
                    host = prompt.stringValue("host"),
                    port = prompt.intValue("port", 22),
                    user = prompt.stringValue("user"),
                    keyType = prompt.stringValue("key_type"),
                    fingerprint = prompt.stringValue("fingerprint"),
                )
            }
    }

    fun resolveHostKey(port: Int, serverName: String, decision: String): Result<Unit> = runCatching {
        val encoded = URLEncoder.encode(serverName, StandardCharsets.UTF_8)
            .replace("+", "%20")
        val body = JsonObject().apply { addProperty("decision", decision) }
        request(port, "POST", "/api/host-key-prompts/$encoded", body)
    }

    fun saveSettings(
        currentPort: Int,
        polling: PollSettings,
        ui: UiSettings,
    ): Result<SaveResult> = runCatching {
        val settings = JsonObject().apply {
            addProperty("gpu_active_interval", polling.gpuActiveSeconds)
            addProperty("gpu_idle_interval", polling.gpuIdleSeconds)
            addProperty("disk_interval", polling.diskSeconds)
            addProperty("process_interval", polling.processSeconds)
            addProperty("idle_threshold", polling.idleThresholdSeconds)
            addProperty("api_port", polling.apiPort)
        }
        val uiObject = JsonObject().apply {
            addProperty("wallpaper_path", ui.wallpaperPath)
            addProperty("default_page", ui.defaultPage)
            addProperty("autostart", ui.autostart)
            addProperty("readability_blur", ui.readabilityBlur)
            addProperty("readability_shade", ui.readabilityShade)
            addProperty("text_mode", ui.textMode)
            addProperty("top_bar_blur", ui.topBarBlur)
            addProperty("bottom_bar_blur", ui.bottomBarBlur)
            addProperty("glass_tint", ui.glassTint)
            addProperty("font_scale", ui.fontScale)
            add("hidden_servers", JsonArray().apply {
                ui.hiddenServers.distinct().forEach { name -> add(name) }
            })
            addProperty("remember_window_bounds", ui.rememberWindowBounds)
            ui.windowBounds?.let { bounds ->
                add("window_bounds", JsonObject().apply {
                    addProperty("x", bounds.x)
                    addProperty("y", bounds.y)
                    addProperty("width", bounds.width)
                    addProperty("height", bounds.height)
                })
            }
        }
        val body = JsonObject().apply {
            add("settings", settings)
            add("ui", uiObject)
        }
        val response = request(currentPort, "POST", "/api/settings", body).asJsonObject
        SaveResult(
            config = parseConfig(response.objectValue("config")),
            restartRequired = response.booleanValue("restart_required"),
        )
    }

    fun addServer(port: Int, draft: ServerDraft): Result<String> = runCatching {
        val body = JsonObject().apply {
            addProperty("name", draft.name.trim())
            addProperty("host", draft.host.trim())
            addProperty("port", draft.port.toIntOrNull() ?: 0)
            addProperty("user", draft.user.trim())
            addProperty("password", draft.password)
            addProperty("key", draft.keyFile.trim())
            addProperty("disk_path", draft.diskPath.trim())
        }
        val response = request(port, "POST", "/api/servers", body).asJsonObject
        response.objectValue("server").stringValue("name", draft.name.trim())
    }

    fun removeServer(port: Int, serverName: String): Result<Unit> = runCatching {
        val encoded = URLEncoder.encode(serverName, StandardCharsets.UTF_8)
            .replace("+", "%20")
        request(port, "DELETE", "/api/servers/$encoded")
    }

    private fun request(
        port: Int,
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonElement {
        val connection = URI.create("http://127.0.0.1:$port$path")
            .toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 1800
        connection.readTimeout = 5000
        connection.requestMethod = method
        connection.useCaches = false
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JsonParser.parseString(text).asJsonObject.stringValue("error", text)
                }.getOrDefault(text.ifBlank { "HTTP $status" })
                error(message)
            }
            return JsonParser.parseString(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseServer(name: String, obj: JsonObject): MonitorServer {
        val cpu = obj.objectValue("cpu")
        val memory = obj.objectValue("memory")
        val disk = obj.objectValue("disk")
        val disks = disk.arrayValue("volumes").map { element ->
            val volume = element.asJsonObject
            MonitorDisk(
                path = volume.stringValue("path"),
                filesystem = volume.stringValue("filesystem"),
                mount = volume.stringValue("mount"),
                totalGb = volume.doubleValue("total"),
                usedGb = volume.doubleValue("used"),
                percent = volume.doubleValue("percent").coerceIn(0.0, 100.0),
            )
        }
        val runningCommands = obj.arrayValue("running_tasks").map { element -> parseTask(element.asJsonObject) }
        val queuedCommands = obj.arrayValue("queued_tasks").map { element -> parseTask(element.asJsonObject) }
        val gpus = obj.arrayValue("gpus").map { element ->
            val gpu = element.asJsonObject
            MonitorGpu(
                index = gpu.intValue("index"),
                name = gpu.stringValue("name"),
                utilization = gpu.doubleValue("util_percent"),
                memoryPercent = gpu.doubleValue("memory_percent"),
                memoryUsedMib = gpu.doubleValue("memory_used_mib"),
                memoryTotalMib = gpu.doubleValue("memory_total_mib"),
                temperatureC = gpu.doubleValue("temp_celsius"),
                powerWatts = gpu.doubleValue("power_watts"),
            )
        }
        return MonitorServer(
            name = obj.stringValue("name", name),
            status = obj.stringValue("status", "unknown"),
            connected = obj.booleanValue("connected"),
            error = obj.stringValue("error"),
            cpuPercent = cpu.doubleValue("percent"),
            cpuCores = cpu.intValue("cores"),
            memoryPercent = memory.doubleValue("percent"),
            memoryUsedGb = memory.doubleValue("used_gb"),
            memoryTotalGb = memory.doubleValue("total_gb"),
            diskPercent = disk.doubleValue("percent"),
            gpus = gpus,
            runningTasks = runningCommands.size,
            queuedTasks = queuedCommands.size,
            lastUpdate = obj.stringValue("last_update"),
            disks = disks,
            runningCommands = runningCommands,
            queuedCommands = queuedCommands,
        )
    }

    private fun parseTask(obj: JsonObject): MonitorTask = MonitorTask(
        pid = obj.stringValue("pid"),
        user = obj.stringValue("user"),
        cpuPercent = obj.doubleValue("cpu_percent"),
        memoryPercent = obj.doubleValue("mem_percent"),
        gpu = obj.stringValue("gpu", "—"),
        state = obj.stringValue("state"),
        command = obj.stringValue("command"),
        elapsed = obj.stringValue("time"),
        note = obj.stringValue("note"),
    )

    private fun parseConfig(root: JsonObject): DashboardConfig {
        val settings = root.objectValue("settings")
        val ui = root.objectValue("ui")
        return DashboardConfig(
            polling = PollSettings(
                gpuActiveSeconds = settings.intValue("gpu_active_interval", 3),
                gpuIdleSeconds = settings.intValue("gpu_idle_interval", 60),
                diskSeconds = settings.intValue("disk_interval", 30),
                processSeconds = settings.intValue("process_interval", 6),
                idleThresholdSeconds = settings.intValue("idle_threshold", 600),
                apiPort = settings.intValue("api_port", DefaultApiPort),
            ),
            ui = UiSettings(
                wallpaperPath = ui.stringValue("wallpaper_path"),
                defaultPage = ui.stringValue("default_page", "home"),
                autostart = ui.booleanValue("autostart"),
                readabilityBlur = ui.booleanValue("readability_blur"),
                readabilityShade = if (ui.value("readability_shade") != null) {
                    ui.booleanValue("readability_shade")
                } else {
                    ui.booleanValue("readability_blur")
                },
                textMode = ui.stringValue("text_mode", "light"),
                topBarBlur = ui.booleanValue("top_bar_blur", true),
                bottomBarBlur = ui.booleanValue("bottom_bar_blur", true),
                glassTint = ui.stringValue("glass_tint", "clear"),
                fontScale = ui.doubleValue("font_scale", 1.08).coerceIn(0.90, 1.35),
                hiddenServers = ui.arrayValue("hidden_servers").mapNotNull { element ->
                    runCatching { element.asString.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
                }.distinct(),
                rememberWindowBounds = ui.booleanValue("remember_window_bounds"),
                windowBounds = ui.value("window_bounds")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { bounds ->
                        MonitorWindowBounds(
                            x = bounds.intValue("x"),
                            y = bounds.intValue("y"),
                            width = bounds.intValue("width"),
                            height = bounds.intValue("height"),
                        ).takeIf { it.width >= 320 && it.height >= 240 }
                    },
            ),
        )
    }
}

internal fun showcaseMonitorServer(): MonitorServer {
    val utilization = listOf(94.0, 87.0, 79.0, 68.0, 56.0, 41.0, 27.0, 12.0)
    val memoryUsed = listOf(72_640.0, 69_120.0, 63_488.0, 58_240.0, 45_824.0, 33_792.0, 21_504.0, 9_216.0)
    val temperatures = listOf(74.0, 72.0, 69.0, 66.0, 62.0, 57.0, 51.0, 44.0)
    val power = listOf(641.0, 618.0, 587.0, 532.0, 476.0, 389.0, 271.0, 146.0)
    val totalMemory = 81_920.0
    return MonitorServer(
        name = "glass-lab-8gpu",
        status = "showcase",
        connected = true,
        error = "",
        cpuPercent = 72.0,
        cpuCores = 128,
        memoryPercent = 81.0,
        memoryUsedGb = 414.7,
        memoryTotalGb = 512.0,
        diskPercent = 67.0,
        gpus = utilization.indices.map { index ->
            MonitorGpu(
                index = index,
                name = "NVIDIA H100 80GB · mock workload ${index + 1}",
                utilization = utilization[index],
                memoryPercent = memoryUsed[index] / totalMemory * 100.0,
                memoryUsedMib = memoryUsed[index],
                memoryTotalMib = totalMemory,
                temperatureC = temperatures[index],
                powerWatts = power[index],
            )
        },
        runningTasks = 12,
        queuedTasks = 5,
        lastUpdate = "synthetic showcase",
    )
}

internal fun showcaseHistory(nowSeconds: Double = System.currentTimeMillis() / 1000.0): List<GpuHistorySeries> {
    val stepSeconds = 300.0
    val sampleCount = 24 * 12 + 1
    return (0 until 8).map { gpuIndex ->
        val points = (0 until sampleCount).map { sampleIndex ->
            val timestamp = nowSeconds - (sampleCount - 1 - sampleIndex) * stepSeconds
            val wave = 49.0 + sin(sampleIndex * 0.15 + gpuIndex * 0.72) * 27.0
            val burst = sin(sampleIndex * 0.037 + gpuIndex * 1.8) * 18.0
            val offset = (gpuIndex % 3) * 4.0 - 4.0
            HistoryPoint(timestamp, (wave + burst + offset).coerceIn(2.0, 99.0))
        }
        GpuHistorySeries(gpuIndex, "H100 GPU $gpuIndex", points)
    }
}

private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

private fun JsonObject.objectValue(key: String): JsonObject =
    value(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.arrayValue(key: String): List<JsonElement> =
    value(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

private fun JsonObject.stringValue(key: String, fallback: String = ""): String =
    value(key)?.let { runCatching { it.asString }.getOrDefault(fallback) } ?: fallback

private fun JsonObject.doubleValue(key: String, fallback: Double = 0.0): Double =
    value(key)?.let { runCatching { it.asDouble }.getOrDefault(fallback) } ?: fallback

private fun JsonObject.intValue(key: String, fallback: Int = 0): Int =
    value(key)?.let { runCatching { it.asInt }.getOrDefault(fallback) } ?: fallback

private fun JsonObject.booleanValue(key: String, fallback: Boolean = false): Boolean =
    value(key)?.let { runCatching { it.asBoolean }.getOrDefault(fallback) } ?: fallback
