import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.roundToInt

private const val API_PORT = 8766

private val Background = Color(0xFF080D1A)
private val PanelText = Color(0xFFF3F7FF)
private val PanelDim = Color(0xFFA8B4CB)
private val PanelMuted = Color(0xFF71809D)
private val Accent = Color(0xFF8DB7FF)
private val AccentStrong = Color(0xFF66A3FF)
private val Green = Color(0xFF62E6A7)
private val Yellow = Color(0xFFFFC76A)
private val Red = Color(0xFFFF778D)
private val SidebarItemHeight = 64.dp
private val SidebarItemSpacing = 8.dp

private enum class DashboardTab(val label: String, val symbol: String) {
    Overview("Overview", "◈"),
    Servers("Servers", "▦"),
    Activity("Activity", "⌁"),
    Settings("Settings", "⚙"),
}

private data class GpuSnapshot(
    val index: Int,
    val name: String,
    val util: Double,
    val memoryPct: Double,
    val memoryUsed: Double,
    val memoryTotal: Double,
    val temperature: Double,
    val power: Double,
)

private data class ServerSnapshot(
    val name: String,
    val status: String,
    val connected: Boolean,
    val error: String,
    val cpuPct: Double,
    val cpuCores: Int,
    val memoryPct: Double,
    val diskPct: Double,
    val memoryUsedGb: Double,
    val memoryTotalGb: Double,
    val gpus: List<GpuSnapshot>,
    val runningTasks: Int,
    val queuedTasks: Int,
    val lastUpdate: String,
)

private object MonitorApi {
    fun fetchServers(port: Int): Result<List<ServerSnapshot>> = runCatching {
        val connection = URI.create("http://127.0.0.1:$port/api/servers")
            .toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 1200
        connection.readTimeout = 1800
        connection.requestMethod = "GET"
        connection.useCaches = false
        try {
            if (connection.responseCode !in 200..299) {
                error("API returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseServers(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseServers(body: String): List<ServerSnapshot> {
        val root = JsonParser.parseString(body).asJsonObject
        return root.entrySet()
            .map { (name, value) -> parseServer(name, value.asJsonObject) }
            .sortedBy { it.name.lowercase() }
    }

    private fun parseServer(name: String, obj: JsonObject): ServerSnapshot {
        val cpu = obj.objectValue("cpu")
        val memory = obj.objectValue("memory")
        val disk = obj.objectValue("disk")
        val gpus = obj.arrayValue("gpus").map { element ->
            val gpu = element.asJsonObject
            GpuSnapshot(
                index = gpu.intValue("index"),
                name = gpu.stringValue("name"),
                util = gpu.doubleValue("util_percent"),
                memoryPct = gpu.doubleValue("memory_percent"),
                memoryUsed = gpu.doubleValue("memory_used_mib"),
                memoryTotal = gpu.doubleValue("memory_total_mib"),
                temperature = gpu.doubleValue("temp_celsius"),
                power = gpu.doubleValue("power_watts"),
            )
        }
        return ServerSnapshot(
            name = obj.stringValue("name", name),
            status = obj.stringValue("status", "unknown"),
            connected = obj.booleanValue("connected"),
            error = obj.stringValue("error"),
            cpuPct = cpu.doubleValue("percent"),
            cpuCores = cpu.intValue("cores"),
            memoryPct = memory.doubleValue("percent"),
            diskPct = disk.doubleValue("percent"),
            memoryUsedGb = memory.doubleValue("used_gb"),
            memoryTotalGb = memory.doubleValue("total_gb"),
            gpus = gpus,
            runningTasks = obj.arrayValue("running_tasks").size,
            queuedTasks = obj.arrayValue("queued_tasks").size,
            lastUpdate = obj.stringValue("last_update"),
        )
    }
}

private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

private fun JsonObject.objectValue(key: String): JsonObject =
    value(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.arrayValue(key: String): List<JsonElement> =
    value(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

private fun JsonObject.stringValue(key: String, fallback: String = ""): String =
    value(key)?.let { runCatching { it.asString }.getOrDefault(fallback) } ?: fallback

private fun JsonObject.doubleValue(key: String): Double =
    value(key)?.let { runCatching { it.asDouble }.getOrDefault(0.0) } ?: 0.0

private fun JsonObject.intValue(key: String): Int = doubleValue(key).roundToInt()

private fun JsonObject.booleanValue(key: String): Boolean =
    value(key)?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false

private fun demoServers(): List<ServerSnapshot> = listOf(
    ServerSnapshot(
        name = "3090-double",
        status = "connected",
        connected = true,
        error = "",
        cpuPct = 38.0,
        cpuCores = 16,
        memoryPct = 54.0,
        diskPct = 41.0,
        memoryUsedGb = 69.1,
        memoryTotalGb = 128.0,
        gpus = listOf(
            GpuSnapshot(0, "RTX 3090", 86.0, 71.0, 16_500.0, 24_576.0, 67.0, 292.0),
            GpuSnapshot(1, "RTX 3090", 23.0, 32.0, 7_900.0, 24_576.0, 54.0, 116.0),
        ),
        runningTasks = 3,
        queuedTasks = 1,
        lastUpdate = "demo",
    ),
    ServerSnapshot(
        name = "4090-1",
        status = "connected",
        connected = true,
        error = "",
        cpuPct = 19.0,
        cpuCores = 24,
        memoryPct = 36.0,
        diskPct = 27.0,
        memoryUsedGb = 46.1,
        memoryTotalGb = 128.0,
        gpus = listOf(
            GpuSnapshot(0, "RTX 4090", 0.0, 0.0, 0.0, 24_576.0, 39.0, 28.0),
        ),
        runningTasks = 0,
        queuedTasks = 0,
        lastUpdate = "demo",
    ),
    ServerSnapshot(
        name = "4090-2",
        status = "connected",
        connected = true,
        error = "",
        cpuPct = 61.0,
        cpuCores = 24,
        memoryPct = 73.0,
        diskPct = 58.0,
        memoryUsedGb = 93.4,
        memoryTotalGb = 128.0,
        gpus = listOf(
            GpuSnapshot(0, "RTX 4090", 64.0, 64.0, 15_700.0, 24_576.0, 71.0, 241.0),
        ),
        runningTasks = 5,
        queuedTasks = 2,
        lastUpdate = "demo",
    ),
)

private fun showcaseServer(): ServerSnapshot {
    val utilization = listOf(94.0, 87.0, 79.0, 68.0, 56.0, 41.0, 27.0, 12.0)
    val memoryUsed = listOf(72_640.0, 69_120.0, 63_488.0, 58_240.0, 45_824.0, 33_792.0, 21_504.0, 9_216.0)
    val temperatures = listOf(74.0, 72.0, 69.0, 66.0, 62.0, 57.0, 51.0, 44.0)
    val power = listOf(641.0, 618.0, 587.0, 532.0, 476.0, 389.0, 271.0, 146.0)
    val memoryTotal = 81_920.0

    return ServerSnapshot(
        name = "glass-lab-8gpu",
        status = "showcase",
        connected = true,
        error = "",
        cpuPct = 72.0,
        cpuCores = 128,
        memoryPct = 81.0,
        diskPct = 67.0,
        memoryUsedGb = 414.7,
        memoryTotalGb = 512.0,
        gpus = utilization.indices.map { index ->
            GpuSnapshot(
                index = index,
                name = "NVIDIA H100 80GB · mock workload ${index + 1}",
                util = utilization[index],
                memoryPct = memoryUsed[index] / memoryTotal * 100.0,
                memoryUsed = memoryUsed[index],
                memoryTotal = memoryTotal,
                temperature = temperatures[index],
                power = power[index],
            )
        },
        runningTasks = 12,
        queuedTasks = 5,
        lastUpdate = "synthetic showcase",
    )
}

private fun withShowcaseServer(servers: List<ServerSnapshot>): List<ServerSnapshot> =
    listOf(showcaseServer()) + servers.filterNot { it.status == "showcase" }

fun main() {
    val bundledBackend = BundledBackendController()
    val launchBounds = MonitorInstallation.launchWindowBounds()
    application {
        val windowState = rememberWindowState(
            position = WindowPosition.Absolute(launchBounds.x.dp, launchBounds.y.dp),
            width = launchBounds.width.dp,
            height = launchBounds.height.dp,
        )
        var showCloseDialog by remember { mutableStateOf(false) }
        var lastFloatingBounds by remember {
            mutableStateOf(Rectangle(launchBounds.x, launchBounds.y, launchBounds.width, launchBounds.height))
        }
        Window(
            onCloseRequest = { showCloseDialog = true },
            title = "GPU Monitor · Kyant Glass",
            state = windowState,
        ) {
            LaunchedEffect(window) {
                window.minimumSize = Dimension(
                    minOf(760, launchBounds.width),
                    minOf(560, launchBounds.height),
                )
            }
            DisposableEffect(window) {
                val listener = object : ComponentAdapter() {
                    private fun captureFloatingBounds() {
                        if (
                            windowState.placement == WindowPlacement.Floating &&
                            !windowState.isMinimized &&
                            window.width > 0 &&
                            window.height > 0
                        ) {
                            lastFloatingBounds = Rectangle(window.bounds)
                        }
                    }

                    override fun componentMoved(event: ComponentEvent?) = captureFloatingBounds()

                    override fun componentResized(event: ComponentEvent?) = captureFloatingBounds()
                }
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { bundledBackend.startIfAvailable() }
            }
            GpuMonitorDashboardV2(
                showCloseDialog = showCloseDialog,
                onDismissCloseDialog = { showCloseDialog = false },
                onMinimizeRequest = { windowState.isMinimized = true },
                onExitRequest = {
                    bundledBackend.stop()
                    MonitorInstallation.persistWindowBoundsIfEnabled(lastFloatingBounds)
                    exitApplication()
                },
            )
        }
    }
}

@Composable
private fun KyantDashboard() {
    val backdrop = rememberLayerBackdrop()
    val navigationBackdrop = rememberLayerBackdrop()
    var servers by remember { mutableStateOf(withShowcaseServer(demoServers())) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var apiOnline by remember { mutableStateOf(false) }
    var apiMessage by remember { mutableStateOf("正在等待 Python 后端…") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf(DashboardTab.Overview) }

    LaunchedEffect(refreshTick) {
        val result = withContext(Dispatchers.IO) { MonitorApi.fetchServers(API_PORT) }
        result.onSuccess { fresh ->
            if (fresh.isNotEmpty()) {
                val visibleServers = withShowcaseServer(fresh)
                servers = visibleServers
                selectedIndex = selectedIndex.coerceIn(0, visibleServers.lastIndex)
                apiOnline = true
                apiMessage = "LIVE · API :$API_PORT"
            } else {
                apiOnline = false
                apiMessage = "API 已连接，但暂无服务器"
            }
        }.onFailure {
            apiOnline = false
            apiMessage = "预览数据 · 后端未连接"
        }
        delay(3000)
        refreshTick++
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        Box(
            modifier = Modifier.fillMaxSize().layerBackdrop(navigationBackdrop),
        ) {
            GlassBackdrop(backdrop)
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GlassHeader(backdrop, apiOnline, apiMessage)
                when (selectedTab) {
                    DashboardTab.Overview -> OverviewPage(
                        backdrop = backdrop,
                        servers = servers,
                        selectedIndex = selectedIndex,
                        onSelected = { selectedIndex = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    DashboardTab.Servers -> ServersPage(
                        backdrop = backdrop,
                        servers = servers,
                        selectedIndex = selectedIndex,
                        onSelected = { selectedIndex = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    DashboardTab.Activity -> ActivityPage(
                        backdrop = backdrop,
                        servers = servers,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    DashboardTab.Settings -> SettingsPage(
                        backdrop = backdrop,
                        apiOnline = apiOnline,
                        apiMessage = apiMessage,
                        onRefresh = { refreshTick++ },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
        ) {
            GlassBottomBar(
                backdrop = navigationBackdrop,
                selectedTab = selectedTab,
                onSelected = { selectedTab = it },
            )
        }
    }
}

@Composable
private fun OverviewPage(
    backdrop: Backdrop,
    servers: List<ServerSnapshot>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val selected = servers.getOrNull(selectedIndex) ?: demoServers().first()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassSidebar(
            backdrop = backdrop,
            servers = servers,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = Modifier.width(280.dp).fillMaxHeight(),
        )
        GlassDetail(
            backdrop = backdrop,
            server = selected,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun ServersPage(
    backdrop: Backdrop,
    servers: List<ServerSnapshot>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val onlineServers = servers.filter { it.connected }
    val averageCpu = if (onlineServers.isEmpty()) 0.0 else onlineServers.map { it.cpuPct }.average()
    GlassSurface(backdrop, modifier, RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("SERVER FLEET", color = PanelMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("所有监控节点", color = PanelText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryChip("Online", "${onlineServers.size}/${servers.size}", Green)
                SummaryChip("GPUs", servers.sumOf { it.gpus.size }.toString(), Accent)
                SummaryChip("Average CPU", "${averageCpu.roundToInt()}%", usageColor(averageCpu))
            }
            servers.forEachIndexed { index, server ->
                ServerStatusRow(
                    server = server,
                    selected = index == selectedIndex,
                    onClick = { onSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun ServerStatusRow(server: ServerSnapshot, selected: Boolean, onClick: () -> Unit) {
    val statusColor = if (server.connected) Green else Red
    val fill = if (selected) Color(0xFF638BFF).copy(alpha = 0.24f) else Color.White.copy(alpha = 0.04f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = statusColor, fontSize = 10.sp)
            Spacer(Modifier.width(9.dp))
            Text(server.name, color = PanelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (server.connected) "ONLINE" else server.status.uppercase(),
                color = statusColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("CPU ${server.cpuPct.roundToInt()}%", color = PanelDim, fontSize = 12.sp)
            Text("RAM ${server.memoryPct.roundToInt()}%", color = PanelDim, fontSize = 12.sp)
            Text("Disk ${server.diskPct.roundToInt()}%", color = PanelDim, fontSize = 12.sp)
            Text("${server.gpus.size} GPU", color = Accent, fontSize = 12.sp)
        }
        if (!server.connected && server.error.isNotBlank()) {
            Text(server.error.take(160), color = Red.copy(alpha = 0.82f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActivityPage(backdrop: Backdrop, servers: List<ServerSnapshot>, modifier: Modifier) {
    val allGpus = servers.flatMap { it.gpus }
    val activeGpus = allGpus.count { it.util >= 5.0 }
    val runningTasks = servers.sumOf { it.runningTasks }
    val queuedTasks = servers.sumOf { it.queuedTasks }
    GlassSurface(backdrop, modifier, RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("ACTIVITY", color = PanelMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("实时任务与负载", color = PanelText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryChip("Running", runningTasks.toString(), Green)
                SummaryChip("Queued", queuedTasks.toString(), Yellow)
                SummaryChip("Active GPUs", "$activeGpus/${allGpus.size}", Accent)
            }
            servers.forEach { server -> ActivityServerRow(server) }
        }
    }
}

@Composable
private fun ActivityServerRow(server: ServerSnapshot) {
    val averageGpu = if (server.gpus.isEmpty()) 0.0 else server.gpus.map { it.util }.average()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("●", color = if (server.connected) Green else Red, fontSize = 10.sp)
        Column(Modifier.weight(1f)) {
            Text(server.name, color = PanelText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (server.connected) "Last update ${server.lastUpdate.ifBlank { "now" }}" else server.error.ifBlank { "Disconnected" }.take(110),
                color = PanelMuted,
                fontSize = 11.sp,
            )
        }
        Text("GPU ${averageGpu.roundToInt()}%", color = usageColor(averageGpu), fontSize = 12.sp)
        Text("Running ${server.runningTasks}", color = Green, fontSize = 12.sp)
        Text("Queued ${server.queuedTasks}", color = Yellow, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsPage(
    backdrop: Backdrop,
    apiOnline: Boolean,
    apiMessage: String,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    GlassSurface(backdrop, modifier, RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("SETTINGS", color = PanelMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("监控与界面设置", color = PanelText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SettingRow("Backend status", apiMessage, if (apiOnline) Green else Yellow)
            SettingRow("Local API", "http://127.0.0.1:$API_PORT", Accent)
            SettingRow("Refresh interval", "3 seconds", PanelText)
            SettingRow("Server configuration", "config.json in the portable folder", PanelText)
            SettingRow("Visual engine", "Kyant Backdrop · vibrancy · blur · lens", Accent)
            SettingRow(
                title = "Refresh now",
                value = "Click to poll the backend immediately",
                valueColor = Green,
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = PanelDim, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun GlassBackdrop(backdrop: LayerBackdrop) {
    Box(
        modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF101F3A), Background, Color(0xFF1D102E)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            )
            drawCircle(
                color = Color(0xFF4F83FF).copy(alpha = 0.22f),
                radius = size.minDimension * 0.40f,
                center = Offset(size.width * 0.20f, size.height * 0.18f),
            )
            drawCircle(
                color = Color(0xFFBF68FF).copy(alpha = 0.17f),
                radius = size.minDimension * 0.35f,
                center = Offset(size.width * 0.88f, size.height * 0.78f),
            )
            drawCircle(
                color = Color(0xFF35E4BB).copy(alpha = 0.10f),
                radius = size.minDimension * 0.22f,
                center = Offset(size.width * 0.62f, size.height * 0.20f),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.075f),
                start = Offset(-size.width * 0.12f, size.height * 0.40f),
                end = Offset(size.width * 0.70f, size.height * 0.08f),
                strokeWidth = size.minDimension * 0.075f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFF7EA9FF).copy(alpha = 0.10f),
                start = Offset(size.width * 0.40f, size.height * 1.08f),
                end = Offset(size.width * 1.08f, size.height * 0.72f),
                strokeWidth = size.minDimension * 0.055f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun GlassHeader(backdrop: Backdrop, online: Boolean, message: String) {
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◈", color = AccentStrong, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("GPU Monitor", color = PanelText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Kyant Liquid Glass prototype", color = PanelMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            StatusPill(online, message)
        }
    }
}

@Composable
private fun GlassSidebar(
    backdrop: Backdrop,
    servers: List<ServerSnapshot>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val sliderOffset by animateDpAsState(
        targetValue = (SidebarItemHeight + SidebarItemSpacing) * selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.76f,
            stiffness = 330f,
        ),
        label = "server-glass-slider",
    )
    val sliderShape = RoundedCornerShape(16.dp)
    GlassSurface(backdrop, modifier, RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("SERVERS", color = PanelMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .offset(y = sliderOffset)
                        .fillMaxWidth()
                        .height(SidebarItemHeight)
                        .clip(sliderShape)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { sliderShape },
                            effects = {
                                vibrancy()
                                blur(10.dp.toPx())
                                lens(27.dp.toPx(), 19.dp.toPx())
                            },
                            highlight = { Highlight.Ambient },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.11f))
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.16f),
                                            Color.White.copy(alpha = 0.04f),
                                            Color.Transparent,
                                        ),
                                        center = Offset(size.width * 0.20f, 0f),
                                        radius = size.width * 0.88f,
                                    )
                                )
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.06f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.07f),
                                        ),
                                        startY = 0f,
                                        endY = size.height,
                                    )
                                )
                            },
                        ),
                    content = { },
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SidebarItemSpacing),
                ) {
                    servers.forEachIndexed { index, server ->
                        ServerItem(
                            server = server,
                            selected = index == selectedIndex,
                            onClick = { onSelected(index) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Backend", color = PanelMuted, fontSize = 11.sp)
            Text("Python · Paramiko · nvidia-smi", color = PanelDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ServerItem(server: ServerSnapshot, selected: Boolean, onClick: () -> Unit) {
    val isShowcase = server.status == "showcase"
    val statusColor = when {
        isShowcase -> AccentStrong
        server.connected -> Green
        else -> Red
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(SidebarItemHeight)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = statusColor, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(server.name, color = PanelText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = if (isShowcase) "MOCK · ${server.gpus.size} GPU · SCROLL TEST"
            else "CPU ${server.cpuPct.roundToInt()}%  ·  ${server.gpus.size} GPU",
            color = if (selected) Accent else PanelDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun GlassDetail(backdrop: Backdrop, server: ServerSnapshot, modifier: Modifier) {
    val isShowcase = server.status == "showcase"
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(server.name, color = PanelText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            isShowcase -> "SYNTHETIC SHOWCASE · scroll through 8 mock GPUs"
                            server.connected -> "Connected · updated ${server.lastUpdate.ifBlank { "now" }}"
                            else -> server.error.ifBlank { "Disconnected" }
                        },
                        color = when {
                            isShowcase -> Accent
                            server.connected -> Green
                            else -> Red
                        },
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${server.runningTasks} active tasks", color = PanelDim, fontSize = 12.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MetricRing("CPU", server.cpuPct, "${server.cpuCores} cores", Modifier.weight(1f))
                MetricRing("Memory", server.memoryPct, "${server.memoryUsedGb} / ${server.memoryTotalGb} GB", Modifier.weight(1f))
                MetricRing("Disk", server.diskPct, "filesystem", Modifier.weight(1f))
                val gpuPct = server.gpus.firstOrNull()?.util ?: 0.0
                MetricRing("GPU", gpuPct, "${server.gpus.size} device(s)", Modifier.weight(1f))
            }

            GlassSection(backdrop, "GPU DEVICES") {
                if (server.gpus.isEmpty()) {
                    Text("No GPU data", color = PanelMuted, fontSize = 13.sp)
                } else {
                    server.gpus.forEach { gpu -> GpuRow(gpu) }
                }
            }

            GlassSection(backdrop, "TASK SUMMARY") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryChip("Running", server.runningTasks.toString(), Green)
                    SummaryChip("Queued", server.queuedTasks.toString(), Yellow)
                    SummaryChip(
                        if (isShowcase) "Source" else "API",
                        if (isShowcase) "MOCK" else if (server.connected) "OK" else "ERR",
                        if (isShowcase || server.connected) Accent else Red,
                    )
                }
            }
    }
}

@Composable
private fun MetricRing(title: String, value: Double, subtitle: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2f + 4.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(
                    color = Color.White.copy(alpha = 0.10f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke),
                )
                drawArc(
                    color = usageColor(value),
                    startAngle = -90f,
                    sweepAngle = value.coerceIn(0.0, 100.0).toFloat() * 3.6f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text("${value.roundToInt()}%", color = PanelText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, color = PanelDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = PanelMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GlassSection(backdrop: Backdrop, title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    lens(36.dp.toPx(), 25.dp.toPx())
                },
                highlight = { Highlight.Ambient },
                onDrawSurface = { },
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = PanelMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun GpuRow(gpu: GpuSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GPU ${gpu.index}", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(gpu.name, color = PanelText, fontSize = 13.sp)
            Text("VRAM ${gpu.memoryUsed.roundToInt()} / ${gpu.memoryTotal.roundToInt()} MiB", color = PanelMuted, fontSize = 11.sp)
        }
        Text("${gpu.util.roundToInt()}%", color = usageColor(gpu.util), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("${gpu.temperature.roundToInt()}°C", color = PanelDim, fontSize = 12.sp)
        Text("${gpu.power.roundToInt()}W", color = PanelDim, fontSize = 12.sp)
    }
}

@Composable
private fun SummaryChip(title: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, color = PanelMuted, fontSize = 10.sp)
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GlassBottomBar(
    backdrop: Backdrop,
    selectedTab: DashboardTab,
    onSelected: (DashboardTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 110.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().height(76.dp),
            shape = RoundedCornerShape(38.dp),
            tintColor = Color.White,
            tintAlpha = 0.06f,
            blurRadius = 13.dp,
            lensRadius = 40.dp,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(8.dp),
            ) {
                val tabs = DashboardTab.entries
                val itemWidth = maxWidth / tabs.size.toFloat()
                val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
                val sliderOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex.toFloat(),
                    animationSpec = spring(
                        dampingRatio = 0.76f,
                        stiffness = 330f,
                    ),
                    label = "liquid-glass-slider",
                )
                val sliderShape = RoundedCornerShape(30.dp)

                Box(
                    modifier = Modifier
                        .offset(x = sliderOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(sliderShape)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { sliderShape },
                            effects = {
                                vibrancy()
                                blur(12.dp.toPx())
                                lens(32.dp.toPx(), 23.dp.toPx())
                            },
                            highlight = { Highlight.Ambient },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.08f))
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.12f),
                                            Color.White.copy(alpha = 0.025f),
                                            Color.Transparent,
                                        ),
                                        center = Offset(size.width * 0.24f, 0f),
                                        radius = size.width * 0.92f,
                                    )
                                )
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.035f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.045f),
                                        ),
                                        startY = 0f,
                                        endY = size.height,
                                    )
                                )
                            },
                        ),
                    content = { },
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    tabs.forEach { tab ->
                        val selected = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(30.dp))
                                .clickable { onSelected(tab) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${tab.symbol}  ${tab.label}",
                                color = if (selected) PanelText else PanelDim,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(online: Boolean, message: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = if (online) Green else Yellow, fontSize = 10.sp)
        Spacer(Modifier.width(7.dp))
        Text(message, color = PanelDim, fontSize = 11.sp)
    }
}

@Composable
private fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier,
    shape: RoundedCornerShape,
    tintColor: Color = Color(0xFF0D1730),
    tintAlpha: Float = 0.30f,
    blurRadius: Dp = 14.dp,
    lensRadius: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(lensRadius.toPx(), (lensRadius.value * 0.72f).dp.toPx())
                },
                highlight = { Highlight.Ambient },
                onDrawSurface = {
                    drawRect(tintColor.copy(alpha = tintAlpha))
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.13f),
                                Color(0xFF8DB7FF).copy(alpha = 0.075f),
                                Color.Transparent,
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height * 0.92f),
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.White.copy(alpha = 0.035f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.18f, 0f),
                            radius = size.width * 0.58f,
                        )
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.075f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.09f),
                            ),
                            startY = 0f,
                            endY = size.height,
                        ),
                    )
                },
            ),
        content = { content() },
    )
}

private fun usageColor(value: Double): Color = when {
    value >= 85.0 -> Red
    value >= 60.0 -> Yellow
    else -> Green
}
