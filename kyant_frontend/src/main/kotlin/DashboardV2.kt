import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class V2Tab(
    val label: String,
    val subtitle: String,
    val key: String,
    val icon: ImageVector,
) {
    Home("Home", "全局状态与需要关注的节点", "home", Icons.Rounded.Home),
    Overview("Overview", "按服务器查看 CPU、内存、硬盘与 GPU", "overview", Icons.Rounded.Dashboard),
    Gpu("GPU", "设备历史曲线与每小时负载", "gpu", Icons.Rounded.Memory),
    Node("Node", "挂载硬盘与运行命令", "node", Icons.Rounded.Storage),
    Settings("Settings", "连接、刷新、外观与启动行为", "settings", Icons.Rounded.Settings),
    ;

    companion object {
        fun fromKey(key: String): V2Tab = entries.firstOrNull { it.key == key.lowercase() } ?: Home
    }
}

private val V2FontScaleOptions = listOf(0.90, 1.00, 1.08, 1.16, 1.25, 1.35)

@Composable
internal fun GpuMonitorDashboardV2(
    showCloseDialog: Boolean,
    onDismissCloseDialog: () -> Unit,
    onMinimizeRequest: () -> Unit,
    onExitRequest: () -> Unit,
) {
    val contentBackdrop = rememberLayerBackdrop()
    val navigationBackdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()

    var apiPort by remember { mutableIntStateOf(MonitorClient.initialPort()) }
    var servers by remember { mutableStateOf<List<MonitorServer>>(emptyList()) }
    var selectedServerName by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(V2Tab.Home) }
    var config by remember { mutableStateOf(DashboardConfig(PollSettings(apiPort = apiPort))) }
    var history by remember { mutableStateOf<List<GpuHistorySeries>>(emptyList()) }
    var apiOnline by remember { mutableStateOf(false) }
    var apiMessage by remember { mutableStateOf("正在连接后端…") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var configTick by remember { mutableIntStateOf(0) }
    var defaultPageApplied by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf("") }
    var addServerBusy by remember { mutableStateOf(false) }
    var addServerMessage by remember { mutableStateOf("") }
    var pendingDeleteServer by remember { mutableStateOf<String?>(null) }
    var removeServerBusyName by remember { mutableStateOf<String?>(null) }
    var removeServerMessage by remember { mutableStateOf("") }

    LaunchedEffect(apiPort, configTick) {
        withContext(Dispatchers.IO) { MonitorClient.fetchConfig(apiPort) }
            .onSuccess { fresh ->
                config = fresh
                if (!defaultPageApplied) {
                    selectedTab = V2Tab.fromKey(fresh.ui.defaultPage)
                    defaultPageApplied = true
                }
            }
    }

    LaunchedEffect(apiPort, refreshTick, config.polling.gpuActiveSeconds, config.ui.hiddenServers) {
        while (true) {
            withContext(Dispatchers.IO) { MonitorClient.fetchServers(apiPort) }
                .onSuccess { fresh ->
                    servers = fresh
                    val visibleFresh = fresh.filterNot { it.name in config.ui.hiddenServers }
                    selectedServerName = when {
                        visibleFresh.isEmpty() -> null
                        selectedServerName == null -> visibleFresh.first().name
                        visibleFresh.none { it.name == selectedServerName } -> visibleFresh.first().name
                        else -> selectedServerName
                    }
                    apiOnline = true
                    apiMessage = if (visibleFresh.isEmpty()) "LIVE · 0/0 在线" else "LIVE · ${visibleFresh.count { it.connected }}/${visibleFresh.size} 在线"
                }
                .onFailure { error ->
                    apiOnline = false
                    apiMessage = "后端未连接 · :$apiPort"
                    if (servers.isEmpty()) selectedServerName = null
                    if (error.message?.isNotBlank() == true) settingsMessage = settingsMessage.ifBlank { "无法连接后端" }
                }
            delay(max(1, config.polling.gpuActiveSeconds) * 1000L)
        }
    }

    LaunchedEffect(apiPort, selectedServerName, refreshTick, config.polling.gpuActiveSeconds) {
        while (true) {
            val name = selectedServerName
            if (name == null) {
                history = emptyList()
            } else {
                withContext(Dispatchers.IO) { MonitorClient.fetchHistory(apiPort, name) }
                    .onSuccess { history = it }
                    .onFailure { history = emptyList() }
            }
            delay(max(2, config.polling.gpuActiveSeconds) * 1000L)
        }
    }

    val visibleServers = servers.filterNot { it.name in config.ui.hiddenServers }
    val selectedIndex = visibleServers.indexOfFirst { it.name == selectedServerName }.coerceAtLeast(0)
    val visibleApiMessage = if (apiOnline) {
        if (visibleServers.isEmpty()) "LIVE · 0/0 在线"
        else "LIVE · ${visibleServers.count { it.connected }}/${visibleServers.size} 在线"
    } else {
        apiMessage
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * config.ui.fontScale.coerceIn(0.90, 1.35).toFloat(),
    )

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalV2ReadabilityBlur provides config.ui.readabilityBlur,
        LocalV2DarkText provides (config.ui.textMode == "dark"),
        LocalV2TopBarBlur provides config.ui.topBarBlur,
        LocalV2BottomBarBlur provides config.ui.bottomBarBlur,
    ) {
        Box(Modifier.fillMaxSize().background(V2Background)) {
            Box(Modifier.fillMaxSize().layerBackdrop(navigationBackdrop)) {
                V2Backdrop(contentBackdrop, config.ui.wallpaperPath)
                when (selectedTab) {
                    V2Tab.Home -> V2HomePage(
                        backdrop = contentBackdrop,
                        servers = visibleServers,
                        onOpenGpu = { name ->
                            selectedServerName = name
                            selectedTab = V2Tab.Gpu
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    )

                    V2Tab.Overview -> V2OverviewPage(
                        backdrop = contentBackdrop,
                        servers = visibleServers,
                        onOpenGpu = { name ->
                            selectedServerName = name
                            selectedTab = V2Tab.Gpu
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    )

                    V2Tab.Gpu -> V2GpuPage(
                        backdrop = contentBackdrop,
                        servers = visibleServers,
                        selectedIndex = selectedIndex,
                        history = history,
                        onSelected = { index -> selectedServerName = visibleServers.getOrNull(index)?.name },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    )

                    V2Tab.Node -> V2NodePage(
                        backdrop = contentBackdrop,
                        servers = visibleServers,
                        selectedIndex = selectedIndex,
                        onSelected = { index -> selectedServerName = visibleServers.getOrNull(index)?.name },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    )

                    V2Tab.Settings -> V2SettingsPage(
                        backdrop = contentBackdrop,
                        config = config,
                        serverCount = visibleServers.size,
                        serverNames = servers.map { it.name },
                        saveBusy = settingsBusy,
                        saveMessage = settingsMessage,
                        addBusy = addServerBusy,
                        addMessage = addServerMessage,
                        serverActionMessage = removeServerMessage,
                        onWallpaperPreview = { path ->
                            config = config.copy(ui = config.ui.copy(wallpaperPath = path))
                        },
                        onReadabilityPreview = { enabled ->
                            config = config.copy(ui = config.ui.copy(readabilityBlur = enabled))
                        },
                        onTextModePreview = { mode ->
                            config = config.copy(ui = config.ui.copy(textMode = mode))
                        },
                        onTopBarBlurPreview = { enabled ->
                            config = config.copy(ui = config.ui.copy(topBarBlur = enabled))
                        },
                        onBottomBarBlurPreview = { enabled ->
                            config = config.copy(ui = config.ui.copy(bottomBarBlur = enabled))
                        },
                        onFontScalePreview = { scale ->
                            config = config.copy(ui = config.ui.copy(fontScale = scale))
                        },
                        onHiddenServersPreview = { names ->
                            config = config.copy(ui = config.ui.copy(hiddenServers = names))
                        },
                        onRequestRemoveServer = { name ->
                            removeServerMessage = ""
                            pendingDeleteServer = name
                        },
                        onSave = { polling, ui ->
                            settingsBusy = true
                            settingsMessage = "正在保存…"
                            scope.launch {
                                withContext(Dispatchers.IO) { MonitorClient.saveSettings(apiPort, polling, ui) }
                                    .onSuccess { result ->
                                        config = result.config
                                        settingsMessage = if (result.restartRequired) {
                                            "已保存。通信端口将在重启 GPU Monitor 后生效"
                                        } else {
                                            "设置已保存并生效"
                                        }
                                    }
                                    .onFailure { settingsMessage = "保存失败：${it.message ?: "未知错误"}" }
                                settingsBusy = false
                            }
                        },
                        onAddServer = { draft ->
                            addServerBusy = true
                            addServerMessage = "正在添加并尝试连接…"
                            scope.launch {
                                withContext(Dispatchers.IO) { MonitorClient.addServer(apiPort, draft) }
                                    .onSuccess { name ->
                                        addServerMessage = "已添加 $name"
                                        selectedServerName = name
                                        refreshTick++
                                    }
                                    .onFailure { addServerMessage = "添加失败：${it.message ?: "未知错误"}" }
                                addServerBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    )
                }
            }

            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp),
            ) {
                V2Header(
                    backdrop = navigationBackdrop,
                    page = selectedTab,
                    apiOnline = apiOnline,
                    apiMessage = visibleApiMessage,
                    onRefresh = { refreshTick++ },
                )
            }

            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
            ) {
                V2BottomBar(navigationBackdrop, selectedTab) { selectedTab = it }
            }

            pendingDeleteServer?.let { name ->
                V2DeleteServerDialog(
                    backdrop = navigationBackdrop,
                    serverName = name,
                    busy = removeServerBusyName == name,
                    message = removeServerMessage,
                    onDismiss = {
                        if (removeServerBusyName == null) pendingDeleteServer = null
                    },
                    onConfirm = {
                        if (removeServerBusyName == null) {
                            removeServerBusyName = name
                            removeServerMessage = "正在删除 $name…"
                            scope.launch {
                                withContext(Dispatchers.IO) { MonitorClient.removeServer(apiPort, name) }
                                    .onSuccess {
                                        config = config.copy(
                                            ui = config.ui.copy(hiddenServers = config.ui.hiddenServers - name)
                                        )
                                        if (selectedServerName == name) selectedServerName = null
                                        removeServerMessage = "已从配置中删除 $name"
                                        pendingDeleteServer = null
                                        refreshTick++
                                    }
                                    .onFailure { error ->
                                        removeServerMessage = "删除失败：${error.message ?: "未知错误"}"
                                    }
                                removeServerBusyName = null
                            }
                        }
                    },
                )
            }

            if (showCloseDialog) {
                V2CloseApplicationDialog(
                    backdrop = navigationBackdrop,
                    onMinimize = onMinimizeRequest,
                    onExit = onExitRequest,
                    onReturn = onDismissCloseDialog,
                )
            }
        }
    }
}

@Composable
private fun V2HomePage(
    backdrop: Backdrop,
    servers: List<MonitorServer>,
    onOpenGpu: (String) -> Unit,
    modifier: Modifier,
) {
    val online = servers.filter { it.connected }
    val allGpus = online.flatMap { server -> server.gpus.map { server.name to it } }
    val averageGpu = allGpus.map { it.second.utilization }.averageOrZero()
    val hotNodes = online.filter { server ->
        server.diskPercent >= 90.0 || server.gpus.any { it.temperatureC >= 80.0 || it.utilization >= 95.0 }
    }
    val issues = servers.filterNot { it.connected }
    val scroll = rememberScrollState()
    val attentionScroll = rememberScrollState()

    Column(
        modifier = modifier.verticalScroll(scroll).padding(top = 110.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        V2GlassSurface(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), tintAlpha = 0.20f) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("CONTROL CENTER", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("今天的 GPU 集群", color = V2Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (issues.isEmpty()) "运行正常" else "${issues.size} 个节点需检查",
                        color = if (issues.isEmpty()) V2Green else V2Yellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    V2SummaryChip("在线服务器", "${online.size}/${servers.size}", if (issues.isEmpty()) V2Green else V2Yellow, Modifier.weight(1f))
                    V2SummaryChip("GPU 设备", allGpus.size.toString(), V2Accent, Modifier.weight(1f))
                    V2SummaryChip("平均 GPU", "${averageGpu.roundToInt()}%", v2UsageColor(averageGpu), Modifier.weight(1f))
                    V2SummaryChip("运行任务", online.sumOf { it.runningTasks }.toString(), V2Cyan, Modifier.weight(1f))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            V2GlassSurface(backdrop, Modifier.weight(1.12f).height(300.dp), RoundedCornerShape(24.dp), tintAlpha = 0.20f) {
                Column(Modifier.fillMaxSize().padding(21.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("GPU LOAD LEADERS", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("当前负载最高的设备", color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (allGpus.isEmpty()) {
                        V2EmptyState("暂无实时 GPU 数据")
                    } else {
                        allGpus.sortedByDescending { it.second.utilization }.take(6).forEach { (serverName, gpu) ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpenGpu(serverName) }
                                    .padding(vertical = 2.dp),
                            ) {
                                V2UsageBar("$serverName · GPU ${gpu.index}", gpu.utilization)
                            }
                        }
                    }
                }
            }

            V2GlassSurface(backdrop, Modifier.weight(0.88f).height(300.dp), RoundedCornerShape(24.dp), tintAlpha = 0.20f) {
                Column(Modifier.fillMaxSize().padding(21.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ATTENTION", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("需要关注", color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val notices = buildList {
                        issues.forEach { add(Triple(it.name, it.error.ifBlank { "连接中断" }, V2Red)) }
                        hotNodes.forEach { server ->
                            val note = when {
                                server.diskPercent >= 90.0 -> "硬盘 ${server.diskPercent.roundToInt()}%"
                                server.gpus.any { it.temperatureC >= 80.0 } -> "GPU 温度偏高"
                                else -> "GPU 接近满载"
                            }
                            add(Triple(server.name, note, V2Yellow))
                        }
                    }.distinctBy { it.first }
                    if (notices.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = V2Green, modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("没有需要处理的告警", color = V2Dim, fontSize = 13.sp)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxWidth().weight(1f).verticalScroll(attentionScroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            notices.forEach { (name, note, color) -> V2NoticeRow(name, note, color) { onOpenGpu(name) } }
                        }
                    }
                }
            }
        }

        V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(154.dp), RoundedCornerShape(24.dp), tintAlpha = 0.18f) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(230.dp)) {
                    Text("CAPACITY", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("集群资源余量", color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("来自当前在线节点的实时均值", color = V2Dim, fontSize = 10.sp)
                }
                V2MetricRing("CPU", online.map { it.cpuPercent }.averageOrZero(), "${online.sumOf { it.cpuCores }} cores", Modifier.weight(1f), 86.dp)
                V2MetricRing("RAM", online.map { it.memoryPercent }.averageOrZero(), "system memory", Modifier.weight(1f), 86.dp)
                V2MetricRing("DISK", online.map { it.diskPercent }.averageOrZero(), "configured path", Modifier.weight(1f), 86.dp)
                V2MetricRing("GPU", averageGpu, "${allGpus.size} devices", Modifier.weight(1f), 86.dp)
            }
        }
    }
}

@Composable
private fun V2NoticeRow(name: String, note: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.035f))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = V2Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun V2OverviewPage(
    backdrop: Backdrop,
    servers: List<MonitorServer>,
    onOpenGpu: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(top = 110.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SERVER OVERVIEW", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("点击卡片进入 GPU 详情", color = V2Dim, fontSize = 11.sp)
        }
        if (servers.isEmpty()) {
            V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(220.dp)) { V2EmptyState("尚未添加服务器，请前往 Settings") }
        } else {
            servers.chunked(2).forEach { rowServers ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowServers.forEach { server ->
                        V2OverviewCard(backdrop, server, Modifier.weight(1f).height(230.dp)) { onOpenGpu(server.name) }
                    }
                    if (rowServers.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun V2OverviewCard(backdrop: Backdrop, server: MonitorServer, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    V2GlassSurface(backdrop, modifier.clip(shape).clickable(onClick = onClick), shape, tintAlpha = 0.20f) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (server.connected) V2Green else V2Red, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(server.name, color = V2Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (server.connected) "${server.gpus.size} GPU · ${server.runningTasks} running · ${server.queuedTasks} queued" else server.error.ifBlank { "offline" },
                        color = V2Dim,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("GPU ${server.averageGpuPercent.roundToInt()}%", color = v2UsageColor(server.averageGpuPercent), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                V2MetricRing("CPU", server.cpuPercent, "${server.cpuCores} cores", Modifier.weight(1f), 82.dp)
                V2MetricRing("RAM", server.memoryPercent, "${server.memoryUsedGb.roundToInt()}/${server.memoryTotalGb.roundToInt()} GB", Modifier.weight(1f), 82.dp)
                V2MetricRing("DISK", server.diskPercent, "configured path", Modifier.weight(1f), 82.dp)
                V2MetricRing("GPU", server.averageGpuPercent, "${server.gpus.size} devices", Modifier.weight(1f), 82.dp)
            }
        }
    }
}

@Composable
private fun V2GpuPage(
    backdrop: Backdrop,
    servers: List<MonitorServer>,
    selectedIndex: Int,
    history: List<GpuHistorySeries>,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val selected = servers.getOrNull(selectedIndex)
    var hours by remember { mutableIntStateOf(24) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        V2ServerSidebar(
            backdrop,
            servers,
            selectedIndex,
            onSelected,
            Modifier.width(270.dp).fillMaxHeight().padding(top = 110.dp, bottom = 112.dp),
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(top = 110.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (selected == null) {
                V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(300.dp)) { V2EmptyState("没有可显示的服务器") }
            } else {
                V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(104.dp), RoundedCornerShape(22.dp), tintAlpha = 0.19f) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 21.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(selected.name, color = V2Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (selected.connected) "${selected.gpus.size} GPU · 最后更新 ${selected.lastUpdate.ifBlank { "刚刚" }}" else selected.error.ifBlank { "连接中断" },
                                color = V2Dim,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        V2SummaryChip("平均负载", "${selected.averageGpuPercent.roundToInt()}%", v2UsageColor(selected.averageGpuPercent))
                        Spacer(Modifier.width(10.dp))
                        V2SummaryChip("任务", "${selected.runningTasks} / ${selected.queuedTasks}", V2Cyan)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HISTORY RANGE", color = V2Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    val hourOptions = listOf(1, 6, 24)
                    V2SegmentedSlider(
                        backdrop = backdrop,
                        labels = hourOptions.map { "${it}h" },
                        selectedIndex = hourOptions.indexOf(hours).coerceAtLeast(0),
                        onSelected = { index -> hours = hourOptions[index] },
                        modifier = Modifier.width(220.dp),
                        height = 36.dp,
                    )
                }
                V2GpuLineChart(backdrop, history, hours, Modifier.fillMaxWidth().height(286.dp))
                V2GpuHeatmap(backdrop, history, Modifier.fillMaxWidth().height(255.dp))
                V2GpuDeviceTable(backdrop, selected)
            }
        }
    }
}

@Composable
private fun V2GpuDeviceTable(backdrop: Backdrop, server: MonitorServer) {
    V2PureGlassSurface(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GPU DEVICES", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (server.gpus.isEmpty()) {
                Text("该服务器没有返回 GPU 数据", color = V2Dim, fontSize = 12.sp)
            } else {
                server.gpus.forEach { gpu ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                            .padding(horizontal = 15.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("GPU ${gpu.index}", color = V2Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
                        Column(Modifier.weight(1f)) {
                            Text(gpu.name, color = V2Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "VRAM ${(gpu.memoryUsedMib / 1024.0).roundToInt()} / ${(gpu.memoryTotalMib / 1024.0).roundToInt()} GB",
                                color = V2Muted,
                                fontSize = 9.sp,
                            )
                        }
                        Text("${gpu.utilization.roundToInt()}%", color = v2UsageColor(gpu.utilization), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp))
                        Text("${gpu.temperatureC.roundToInt()}°C", color = if (gpu.temperatureC >= 80) V2Red else V2Dim, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                        Text("${gpu.powerWatts.roundToInt()} W", color = V2Dim, fontSize = 11.sp, modifier = Modifier.width(62.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun V2NodePage(
    backdrop: Backdrop,
    servers: List<MonitorServer>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val selected = servers.getOrNull(selectedIndex)
    var copyMessage by remember { mutableStateOf("") }
    LaunchedEffect(copyMessage) {
        if (copyMessage.isNotBlank()) {
            delay(1800)
            copyMessage = ""
        }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        V2ServerSidebar(
            backdrop,
            servers,
            selectedIndex,
            onSelected,
            Modifier.width(270.dp).fillMaxHeight().padding(top = 110.dp, bottom = 112.dp),
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(top = 110.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (selected == null) {
                V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(300.dp)) {
                    V2EmptyState("没有可显示的服务器")
                }
            } else {
                V2GlassSurface(
                    backdrop,
                    Modifier.fillMaxWidth().height(104.dp),
                    RoundedCornerShape(22.dp),
                    tintAlpha = 0.19f,
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 21.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(selected.name, color = V2Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (selected.connected) "当前节点 · ${selected.lastUpdate.ifBlank { "刚刚" }}" else selected.error.ifBlank { "连接中断" },
                                color = V2Dim,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        V2SummaryChip("挂载盘", selected.disks.size.toString(), V2Accent)
                        Spacer(Modifier.width(10.dp))
                        V2SummaryChip("运行 / 排队", "${selected.runningTasks} / ${selected.queuedTasks}", V2Cyan)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MOUNTED STORAGE", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    val used = selected.disks.sumOf { it.usedGb }
                    val total = selected.disks.sumOf { it.totalGb }
                    Text(
                        if (selected.disks.isEmpty()) "等待磁盘刷新" else "${formatDiskSize(used)} / ${formatDiskSize(total)}",
                        color = V2Dim,
                        fontSize = 11.sp,
                    )
                }
                if (selected.disks.isEmpty()) {
                    V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(160.dp), RoundedCornerShape(22.dp), tintAlpha = 0.17f) {
                        V2EmptyState("该服务器尚未返回 /data* 或 /root/data* 挂载盘")
                    }
                } else {
                    selected.disks.chunked(2).forEach { rowDisks ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            rowDisks.forEach { disk ->
                                V2StorageCard(backdrop, disk, Modifier.weight(1f).height(158.dp))
                            }
                            if (rowDisks.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                V2GlassSurface(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), tintAlpha = 0.18f) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("COMMANDS", color = V2Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("当前进程命令", color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                copyMessage.ifBlank { "单击任务行复制全部字段与完整 Command" },
                                color = if (copyMessage.isBlank()) V2Dim else V2Green,
                                fontSize = 10.sp,
                            )
                        }
                        V2TaskTable(
                            title = "RUNNING COMMANDS",
                            tasks = selected.runningCommands,
                            queued = false,
                            onCopy = { task ->
                                copyMessage = if (copyTaskToClipboard(task)) "已复制 PID ${task.pid}" else "复制失败"
                            },
                        )
                        V2TaskTable(
                            title = "QUEUED COMMANDS",
                            tasks = selected.queuedCommands,
                            queued = true,
                            onCopy = { task ->
                                copyMessage = if (copyTaskToClipboard(task)) "已复制 PID ${task.pid}" else "复制失败"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun V2StorageCard(backdrop: Backdrop, disk: MonitorDisk, modifier: Modifier) {
    val usageColor = v2UsageColor(disk.percent)
    V2PureGlassSurface(backdrop, modifier, RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Storage, null, tint = usageColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        disk.path.ifBlank { disk.mount },
                        color = V2Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${disk.mount} · ${disk.filesystem}",
                        color = V2Muted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("${disk.percent.roundToInt()}%", color = usageColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
            ) {
                Box(
                    Modifier.fillMaxWidth((disk.percent / 100.0).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(usageColor.copy(alpha = 0.78f)),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text("已用 ${formatDiskSize(disk.usedGb)}", color = V2Dim, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text("可用 ${formatDiskSize(disk.availableGb)}", color = V2Dim, fontSize = 10.sp)
            }
            Text("总容量 ${formatDiskSize(disk.totalGb)}", color = V2Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun V2TaskTable(
    title: String,
    tasks: List<MonitorTask>,
    queued: Boolean,
    onCopy: (MonitorTask) -> Unit,
) {
    Text("$title · ${tasks.size}", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PID", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(58.dp))
        Text("USER", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(78.dp))
        Text("CPU", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(54.dp))
        Text("MEM", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(54.dp))
        Text(if (queued) "STATE" else "GPU", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(62.dp))
        Text("TIME", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(76.dp))
        Text("COMMAND", color = V2Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text("NOTE", color = V2Muted, fontSize = 9.sp, modifier = Modifier.width(150.dp))
    }
    if (tasks.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.025f))
                .padding(horizontal = 12.dp, vertical = 13.dp),
        ) {
            Text(if (queued) "当前没有排队或暂停任务" else "当前没有运行任务", color = V2Muted, fontSize = 10.sp)
        }
    } else {
        tasks.forEachIndexed { index, task ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (index % 2 == 0) 0.040f else 0.024f))
                    .clickable { onCopy(task) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(task.pid, color = V2Text, fontSize = 10.sp, modifier = Modifier.width(58.dp), maxLines = 1)
                Text(task.user, color = V2Dim, fontSize = 10.sp, modifier = Modifier.width(78.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${task.cpuPercent.roundToInt()}%", color = v2UsageColor(task.cpuPercent), fontSize = 10.sp, modifier = Modifier.width(54.dp))
                Text("${task.memoryPercent.roundToInt()}%", color = V2Dim, fontSize = 10.sp, modifier = Modifier.width(54.dp))
                Text(
                    if (queued) task.state.ifBlank { "—" } else task.gpu.ifBlank { "—" },
                    color = if (!queued && task.gpu != "—") V2Green else V2Dim,
                    fontSize = 10.sp,
                    modifier = Modifier.width(62.dp),
                    maxLines = 1,
                )
                Text(task.elapsed, color = V2Dim, fontSize = 10.sp, modifier = Modifier.width(76.dp), maxLines = 1)
                Text(
                    task.command,
                    color = V2Text,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    task.note.ifBlank { "—" },
                    color = V2Muted,
                    fontSize = 9.sp,
                    modifier = Modifier.width(150.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun V2DeleteServerDialog(
    backdrop: Backdrop,
    serverName: String,
    busy: Boolean,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    V2GlassDialogFrame(backdrop, onDismiss) {
        Text("DELETE SERVER", color = V2Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("删除 $serverName？", color = V2Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "这会从 config.json 删除该服务器的地址、登录方式与监控配置。隐藏服务器不会产生这些影响。",
            color = V2Dim,
            fontSize = 11.sp,
        )
        if (message.isNotBlank()) {
            Text(message, color = if (message.startsWith("删除失败")) V2Red else V2Yellow, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            V2GlassDialogButton(
                backdrop = backdrop,
                label = "返回",
                color = V2Accent,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onDismiss,
            )
            V2GlassDialogButton(
                backdrop = backdrop,
                label = if (busy) "正在删除…" else "确认删除",
                color = V2Red,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun V2CloseApplicationDialog(
    backdrop: Backdrop,
    onMinimize: () -> Unit,
    onExit: () -> Unit,
    onReturn: () -> Unit,
) {
    V2GlassDialogFrame(backdrop, onReturn) {
        Text("CLOSE GPU MONITOR", color = V2Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("关闭软件？", color = V2Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "监控后端会在直接关闭时一并停止。也可以只把窗口最小化到任务栏，继续保留当前监控。",
            color = V2Dim,
            fontSize = 11.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V2GlassDialogButton(
                backdrop = backdrop,
                label = "最小化到任务栏",
                color = V2Accent,
                modifier = Modifier.weight(1.25f),
                onClick = {
                    onReturn()
                    onMinimize()
                },
            )
            V2GlassDialogButton(
                backdrop = backdrop,
                label = "直接关闭",
                color = V2Red,
                modifier = Modifier.weight(1f),
                onClick = onExit,
            )
            V2GlassDialogButton(
                backdrop = backdrop,
                label = "返回",
                color = V2Green,
                modifier = Modifier.weight(0.82f),
                onClick = onReturn,
            )
        }
    }
}

@Composable
private fun V2GlassDialogFrame(
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val outsideInteraction = remember { MutableInteractionSource() }
    val insideInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)).clickable(
            interactionSource = outsideInteraction,
            indication = null,
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(30.dp)
        V2GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.width(540.dp).clip(shape).clickable(
                interactionSource = insideInteraction,
                indication = null,
                onClick = {},
            ),
            shape = shape,
            tintColor = Color(0xFF0C162C),
            tintAlpha = 0.30f,
            blurRadius = 22.dp,
            lensRadius = 48.dp,
            enableBlur = true,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(25.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun V2GlassDialogButton(
    backdrop: Backdrop,
    label: String,
    color: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(23.dp)
    V2GlassSurface(
        backdrop = backdrop,
        modifier = modifier.height(46.dp).clip(shape).clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        tintColor = color,
        tintAlpha = if (enabled) 0.12f else 0.045f,
        blurRadius = 10.dp,
        lensRadius = 26.dp,
        enableBlur = true,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (enabled) V2Text else V2Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun V2SettingsPage(
    backdrop: Backdrop,
    config: DashboardConfig,
    serverCount: Int,
    serverNames: List<String>,
    saveBusy: Boolean,
    saveMessage: String,
    addBusy: Boolean,
    addMessage: String,
    serverActionMessage: String,
    onWallpaperPreview: (String) -> Unit,
    onReadabilityPreview: (Boolean) -> Unit,
    onTextModePreview: (String) -> Unit,
    onTopBarBlurPreview: (Boolean) -> Unit,
    onBottomBarBlurPreview: (Boolean) -> Unit,
    onFontScalePreview: (Double) -> Unit,
    onHiddenServersPreview: (List<String>) -> Unit,
    onRequestRemoveServer: (String) -> Unit,
    onSave: (PollSettings, UiSettings) -> Unit,
    onAddServer: (ServerDraft) -> Unit,
    modifier: Modifier,
) {
    var wallpaper by remember { mutableStateOf(config.ui.wallpaperPath) }
    var apiPortText by remember { mutableStateOf(config.polling.apiPort.toString()) }
    var activeSeconds by remember { mutableIntStateOf(config.polling.gpuActiveSeconds) }
    var idleSeconds by remember { mutableIntStateOf(config.polling.gpuIdleSeconds) }
    var diskSeconds by remember { mutableIntStateOf(config.polling.diskSeconds) }
    var processSeconds by remember { mutableIntStateOf(config.polling.processSeconds) }
    var idleThreshold by remember { mutableIntStateOf(config.polling.idleThresholdSeconds) }
    var defaultPage by remember { mutableStateOf(config.ui.defaultPage) }
    var autostart by remember { mutableStateOf(config.ui.autostart) }
    var readabilityBlur by remember { mutableStateOf(config.ui.readabilityBlur) }
    var textMode by remember { mutableStateOf(config.ui.textMode) }
    var topBarBlur by remember { mutableStateOf(config.ui.topBarBlur) }
    var bottomBarBlur by remember { mutableStateOf(config.ui.bottomBarBlur) }
    var fontScale by remember { mutableStateOf(config.ui.fontScale) }
    var hiddenServers by remember { mutableStateOf(config.ui.hiddenServers.toSet()) }
    var rememberWindowBounds by remember { mutableStateOf(config.ui.rememberWindowBounds) }
    var draft by remember { mutableStateOf(ServerDraft()) }

    LaunchedEffect(config) {
        wallpaper = config.ui.wallpaperPath
        apiPortText = config.polling.apiPort.toString()
        activeSeconds = config.polling.gpuActiveSeconds
        idleSeconds = config.polling.gpuIdleSeconds
        diskSeconds = config.polling.diskSeconds
        processSeconds = config.polling.processSeconds
        idleThreshold = config.polling.idleThresholdSeconds
        defaultPage = config.ui.defaultPage
        autostart = config.ui.autostart
        readabilityBlur = config.ui.readabilityBlur
        textMode = config.ui.textMode
        topBarBlur = config.ui.topBarBlur
        bottomBarBlur = config.ui.bottomBarBlur
        fontScale = config.ui.fontScale
        hiddenServers = config.ui.hiddenServers.toSet()
        rememberWindowBounds = config.ui.rememberWindowBounds
    }

    fun setServerHidden(name: String, hidden: Boolean) {
        val next = if (hidden) hiddenServers + name else hiddenServers - name
        hiddenServers = next
        onHiddenServersPreview(next.sorted())
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(top = 110.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            V2SettingsSection(backdrop, "APPEARANCE", "外观与默认页面", Icons.Rounded.Image, Modifier.weight(1f)) {
                Text("壁纸", color = V2Dim, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    V2Field(wallpaper, { wallpaper = it }, "图片路径", Modifier.weight(1f))
                    V2ActionButton("选择", Icons.Rounded.Image, false) {
                        chooseWallpaper()?.let {
                            wallpaper = it
                            onWallpaperPreview(it)
                        }
                    }
                    V2ActionButton("清除", Icons.Rounded.Refresh, false) {
                        wallpaper = ""
                        onWallpaperPreview("")
                    }
                }
                Text("选择后立即预览，点击保存后永久生效。", color = V2Muted, fontSize = 9.sp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("文字颜色", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("根据壁纸明暗选择整套文字颜色", color = V2Muted, fontSize = 9.sp)
                    }
                    val textModes = listOf("light", "dark")
                    V2SegmentedSlider(
                        backdrop = backdrop,
                        labels = listOf("亮色文字", "暗色文字"),
                        selectedIndex = textModes.indexOf(textMode).coerceAtLeast(0),
                        onSelected = { index ->
                            textMode = textModes[index]
                            onTextModePreview(textMode)
                        },
                        modifier = Modifier.width(220.dp),
                        height = 34.dp,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(128.dp)) {
                        Text("字体大小", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("当前 ${(fontScale * 100).roundToInt()}%", color = V2Muted, fontSize = 9.sp)
                    }
                    val selectedFontIndex = V2FontScaleOptions.indices.minByOrNull { index ->
                        kotlin.math.abs(V2FontScaleOptions[index] - fontScale)
                    } ?: 2
                    V2SegmentedSlider(
                        backdrop = backdrop,
                        labels = V2FontScaleOptions.map { "${(it * 100).roundToInt()}%" },
                        selectedIndex = selectedFontIndex,
                        onSelected = { index ->
                            fontScale = V2FontScaleOptions[index]
                            onFontScalePreview(fontScale)
                        },
                        modifier = Modifier.weight(1f),
                        height = 34.dp,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .clickable {
                            readabilityBlur = !readabilityBlur
                            onReadabilityPreview(readabilityBlur)
                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("增加可读性", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("打开后增加暗层与玻璃模糊；关闭时保留纯折射玻璃", color = V2Muted, fontSize = 9.sp)
                    }
                    Switch(
                        checked = readabilityBlur,
                        onCheckedChange = {
                            readabilityBlur = it
                            onReadabilityPreview(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = V2AccentStrong, checkedTrackColor = V2AccentStrong.copy(alpha = 0.45f)),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .clickable {
                            topBarBlur = !topBarBlur
                            onTopBarBlurPreview(topBarBlur)
                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("顶栏模糊", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("单独控制悬浮顶栏的背景模糊", color = V2Muted, fontSize = 9.sp)
                    }
                    Switch(
                        checked = topBarBlur,
                        onCheckedChange = {
                            topBarBlur = it
                            onTopBarBlurPreview(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = V2AccentStrong, checkedTrackColor = V2AccentStrong.copy(alpha = 0.45f)),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .clickable {
                            bottomBarBlur = !bottomBarBlur
                            onBottomBarBlurPreview(bottomBarBlur)
                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("底栏模糊", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("单独控制底栏与玻璃滑块，不受增加可读性影响", color = V2Muted, fontSize = 9.sp)
                    }
                    Switch(
                        checked = bottomBarBlur,
                        onCheckedChange = {
                            bottomBarBlur = it
                            onBottomBarBlurPreview(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = V2AccentStrong, checkedTrackColor = V2AccentStrong.copy(alpha = 0.45f)),
                    )
                }
                Text("进入后默认页面", color = V2Dim, fontSize = 11.sp)
                V2SegmentedSlider(
                    backdrop = backdrop,
                    labels = V2Tab.entries.map { it.label },
                    selectedIndex = V2Tab.entries.indexOfFirst { it.key == defaultPage }.coerceAtLeast(0),
                    onSelected = { index -> defaultPage = V2Tab.entries[index].key },
                    modifier = Modifier.fillMaxWidth(),
                    height = 36.dp,
                )
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .clickable { autostart = !autostart }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("开机自启动", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("登录 Windows 后自动启动 GPU Monitor", color = V2Muted, fontSize = 9.sp)
                    }
                    Switch(
                        checked = autostart,
                        onCheckedChange = { autostart = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = V2AccentStrong, checkedTrackColor = V2AccentStrong.copy(alpha = 0.45f)),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f))
                        .clickable { rememberWindowBounds = !rememberWindowBounds }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记忆窗口位置和大小", color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("关闭软件时保存坐标与尺寸；下次打开会校正到当前屏幕内", color = V2Muted, fontSize = 9.sp)
                    }
                    Switch(
                        checked = rememberWindowBounds,
                        onCheckedChange = { rememberWindowBounds = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = V2AccentStrong, checkedTrackColor = V2AccentStrong.copy(alpha = 0.45f)),
                    )
                }
            }

            V2SettingsSection(backdrop, "CONNECTION", "通信与刷新频率", Icons.Rounded.Refresh, Modifier.weight(1f)) {
                V2Field(apiPortText, { apiPortText = it.filter(Char::isDigit).take(5) }, "前后端通信端口", Modifier.fillMaxWidth())
                V2IntervalRow(backdrop, "活跃 GPU", activeSeconds, listOf(1, 3, 5, 10)) { activeSeconds = it }
                V2IntervalRow(backdrop, "空闲 GPU", idleSeconds, listOf(30, 60, 120, 300)) { idleSeconds = it }
                V2IntervalRow(backdrop, "硬盘", diskSeconds, listOf(10, 30, 60, 120, 300)) { diskSeconds = it }
                V2IntervalRow(backdrop, "任务列表", processSeconds, listOf(3, 6, 10, 30, 60)) { processSeconds = it }
                V2IntervalRow(backdrop, "空闲判定", idleThreshold, listOf(300, 600, 1200, 1800), "s") { idleThreshold = it }
                Text("修改通信端口后需要重启软件；其余刷新项立即生效。", color = V2Muted, fontSize = 9.sp)
            }
        }

        V2SettingsSection(
            backdrop,
            "SERVER VISIBILITY",
            "服务器显示与删除",
            Icons.Rounded.Computer,
            Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "隐藏只影响界面与统计；删除会从 config.json 移除服务器。",
                    color = V2Muted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "显示 $serverCount / ${serverNames.size}",
                    color = V2Dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (serverNames.isEmpty()) {
                Text("尚未添加服务器", color = V2Muted, fontSize = 11.sp)
            } else {
                serverNames.chunked(2).forEach { rowNames ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowNames.forEach { name ->
                            val hidden = name in hiddenServers
                            Row(
                                Modifier.weight(1f).clip(RoundedCornerShape(17.dp))
                                    .background(Color.White.copy(alpha = 0.035f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = V2Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (hidden) "已隐藏 · 不参与界面统计" else "显示中 · 参与界面统计",
                                        color = if (hidden) V2Yellow else V2Muted,
                                        fontSize = 9.sp,
                                    )
                                }
                                Switch(
                                    checked = hidden,
                                    onCheckedChange = { checked -> setServerHidden(name, checked) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = V2Yellow,
                                        checkedTrackColor = V2Yellow.copy(alpha = 0.38f),
                                    ),
                                )
                                Spacer(Modifier.width(9.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(13.dp))
                                        .background(V2Red.copy(alpha = 0.11f))
                                        .clickable { onRequestRemoveServer(name) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("删除", color = V2Red, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (rowNames.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (serverActionMessage.isNotBlank()) {
                Text(
                    serverActionMessage,
                    color = if (serverActionMessage.startsWith("删除失败")) V2Red else V2Green,
                    fontSize = 11.sp,
                )
            }
        }

        V2GlassSurface(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), tintAlpha = 0.20f) {
            Column(Modifier.fillMaxWidth().padding(21.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = V2AccentStrong, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("ADD SERVER", color = V2Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("添加 SSH 服务器", color = V2Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("当前 $serverCount 台", color = V2Dim, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V2Field(draft.name, { draft = draft.copy(name = it) }, "显示名称", Modifier.weight(1f))
                    V2Field(draft.host, { draft = draft.copy(host = it) }, "地址 / IP", Modifier.weight(1.2f))
                    V2Field(draft.port, { draft = draft.copy(port = it.filter(Char::isDigit).take(5)) }, "SSH 端口", Modifier.width(120.dp))
                    V2Field(draft.user, { draft = draft.copy(user = it) }, "用户名", Modifier.weight(0.8f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    V2Field(draft.password, { draft = draft.copy(password = it) }, "密码（可留空）", Modifier.weight(1f), secret = true)
                    V2Field(draft.keyFile, { draft = draft.copy(keyFile = it) }, "SSH 私钥路径（可选）", Modifier.weight(1.2f))
                    V2Field(draft.diskPath, { draft = draft.copy(diskPath = it) }, "监控硬盘路径", Modifier.weight(0.8f))
                    V2ActionButton("添加服务器", Icons.Rounded.Add, addBusy) {
                        if (draft.name.isNotBlank() && draft.host.isNotBlank() && draft.user.isNotBlank()) onAddServer(draft)
                    }
                }
                Text("密码仅写入本机 config.json；安全性要求高时建议使用 SSH 私钥。", color = V2Muted, fontSize = 9.sp)
                if (addMessage.isNotBlank()) Text(addMessage, color = if (addMessage.startsWith("添加失败")) V2Red else V2Green, fontSize = 11.sp)
            }
        }

        V2GlassSurface(backdrop, Modifier.fillMaxWidth().height(82.dp), RoundedCornerShape(22.dp), tintAlpha = 0.18f) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Computer, null, tint = V2Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("保存设置", color = V2Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(saveMessage.ifBlank { "设置保存在软件目录的 config.json 中" }, color = V2Dim, fontSize = 10.sp)
                }
                V2ActionButton("保存并应用", Icons.Rounded.Save, saveBusy) {
                    val port = apiPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: DefaultApiPort
                    onSave(
                        PollSettings(activeSeconds, idleSeconds, diskSeconds, processSeconds, idleThreshold, port),
                        UiSettings(
                            wallpaperPath = wallpaper,
                            defaultPage = defaultPage,
                            autostart = autostart,
                            readabilityBlur = readabilityBlur,
                            textMode = textMode,
                            topBarBlur = topBarBlur,
                            bottomBarBlur = bottomBarBlur,
                            fontScale = fontScale,
                            hiddenServers = hiddenServers.sorted(),
                            rememberWindowBounds = rememberWindowBounds,
                            windowBounds = config.ui.windowBounds,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun V2SettingsSection(
    backdrop: Backdrop,
    eyebrow: String,
    title: String,
    icon: ImageVector,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    V2GlassSurface(backdrop, modifier, RoundedCornerShape(24.dp), tintAlpha = 0.20f) {
        Column(Modifier.fillMaxWidth().padding(21.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = V2AccentStrong, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(eyebrow, color = V2Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(title, color = V2Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            content()
        }
    }
}

@Composable
private fun V2IntervalRow(
    backdrop: Backdrop,
    label: String,
    selected: Int,
    options: List<Int>,
    suffix: String = "s",
    onSelected: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = V2Dim, fontSize = 11.sp, modifier = Modifier.width(74.dp))
        V2SegmentedSlider(
            backdrop = backdrop,
            labels = options.map { value -> "$value$suffix" },
            selectedIndex = options.indexOf(selected).coerceAtLeast(0),
            onSelected = { index -> onSelected(options[index]) },
            modifier = Modifier.weight(1f),
            height = 34.dp,
        )
    }
}

@Composable
private fun V2ActionButton(label: String, icon: ImageVector, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(15.dp)).background(V2AccentStrong.copy(alpha = if (busy) 0.10f else 0.20f))
            .clickable(enabled = !busy, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (busy) Icons.Rounded.Refresh else icon, null, tint = if (busy) V2Muted else V2Accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (busy) "请稍候" else label, color = if (busy) V2Muted else V2Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun V2Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        modifier = modifier,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = V2Text,
            cursorColor = V2Accent,
            focusedBorderColor = V2Accent.copy(alpha = 0.75f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.11f),
            focusedLabelColor = V2Accent,
            unfocusedLabelColor = V2Muted,
            backgroundColor = Color.White.copy(alpha = 0.025f),
        ),
    )
}

@Composable
private fun V2EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = V2Muted, fontSize = 13.sp)
    }
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun formatDiskSize(gigabytes: Double): String = when {
    gigabytes >= 1024.0 -> "%.2f TB".format(gigabytes / 1024.0)
    else -> "%.1f GB".format(gigabytes)
}

private fun copyTaskToClipboard(task: MonitorTask): Boolean = runCatching {
    val text = listOf(
        "PID: ${task.pid}",
        "User: ${task.user}",
        "CPU%: ${task.cpuPercent}",
        "Mem%: ${task.memoryPercent}",
        "GPU: ${task.gpu}",
        "State: ${task.state}",
        "Command: ${task.command}",
        "Time: ${task.elapsed}",
        "Note: ${task.note}",
    ).joinToString("\n")
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    true
}.getOrDefault(false)

private fun chooseWallpaper(): String? {
    val dialog = FileDialog(null as Frame?, "选择 GPU Monitor 壁纸", FileDialog.LOAD)
    dialog.filenameFilter = java.io.FilenameFilter { _, name ->
        name.lowercase().endsWith(".png") || name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".webp")
    }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}
