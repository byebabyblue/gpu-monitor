import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import javax.swing.SwingUtilities
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in, offscreen rendering of the actual components, with synthetic data. */
class VisualSmokeTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderGpuAndNetworkPreviewsWhenRequested() {
        val output = System.getenv("XERVER_VISUAL_QA_DIR")?.let(::File) ?: return
        output.mkdirs()
        SwingUtilities.invokeAndWait {
            for (width in listOf(760, 1100, 1600)) {
            for (fontScale in listOf(1.35f)) {
            for (darkText in listOf(false, true)) {
                for (page in (if (width == 1600) listOf("settings", "overview", "gpu", "network", "home", "node", "palette-clear", "palette-ice", "palette-violet", "palette-aqua", "palette-warm") else listOf("settings", "overview", "gpu", "network", "home", "node"))) {
                    val scene = ImageComposeScene(width = width, height = 1500, density = Density(1f, fontScale)) {
                        CompositionLocalProvider(androidx.compose.material.LocalTextStyle provides XerverTextStyle, LocalV2DarkText provides darkText, LocalV2CardBlurLevel provides BlurLevelMedium, LocalV2GlassTint provides if (page.startsWith("palette-")) page.removePrefix("palette-") else "clear") {
                            val backdrop = rememberLayerBackdrop()
                            val sample = showcaseMonitorServer().copy(name = "计算节点一", lastUpdate = "2026-10-18 12:00:00", gpus = showcaseMonitorServer().gpus.take(2).map { it.copy(name = "NVIDIA H100 80GB") },
                                disks = (1..6).map { MonitorDisk("/data$it", "storage-$it:/volume", "/data$it", 4000.0, 1800.0 + it * 200, 45.0 + it * 5) },
                                networkCheckedAt = System.currentTimeMillis() / 1000.0,
                                network = listOf(
                                    NetworkProbe("百度", "www.baidu.com", 18.0, "ok", 83.0, "ok", 200),
                                    NetworkProbe("GitHub", "github.com", null, "timeout", 236.0, "ok", 200),
                                    NetworkProbe("清华 PyPI 源", "pypi.tuna.tsinghua.edu.cn", 25.0, "ok", 98.0, "ok", 200),
                                    NetworkProbe("PyTorch 官方源", "download.pytorch.org", null, "timeout", null, "failed", 0),
                                ))
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    drawRect(Brush.linearGradient(listOf(Color(0xFF8DB7D8), Color(0xFFE1C2A6), Color(0xFF90AFC8)), end = Offset(size.width, size.height)))
                                    repeat(10) { index ->
                                        drawCircle(if (index % 2 == 0) Color(0xFF23485B).copy(alpha = 0.35f) else Color(0xFFF8E2C5).copy(alpha = 0.5f),
                                            radius = size.width * 0.12f, center = Offset(index * size.width / 8f, (index % 4) * size.height / 3f))
                                    }
                                }
                                val serverSamples = if (width == 760) (1..8).map { sample.copy(name = "计算节点%02d".format(it)) } else listOf(sample)
                                if (page.startsWith("palette-")) Box(Modifier.padding(64.dp)) {
                                    V2GlassSurface(backdrop, Modifier.fillMaxSize()) {
                                        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            Text("玻璃色调预览", color = V2Text, fontSize = 24.sp)
                                            Text("中文正文 · 网络检测 · 显存占用", color = V2Text, fontSize = 16.sp)
                                            Text("72%    24 小时    18.5 GiB", color = V2Cyan, fontSize = 24.sp)
                                        }
                                    }
                                }
                                else if (page == "gpu") V2GpuPage(backdrop, serverSamples, 0, showcaseHistory().take(2), 1440, {}, {}, Modifier.fillMaxSize().padding(24.dp))
                                else if (page == "home") V2HomePage(backdrop, listOf(sample), {}, Modifier.fillMaxSize().padding(24.dp))
                                else if (page == "node") V2NodePage(backdrop, serverSamples, 0, {}, Modifier.fillMaxSize().padding(24.dp))
                                else if (page == "overview") V2OverviewPage(backdrop, listOf(sample), {}, Modifier.fillMaxSize().padding(24.dp))
                                else if (page == "settings") V2SettingsPage(
                                    backdrop, DashboardConfig(), 0, 1, listOf(sample.name), false, "", false, "", "", false, "", 0L,
                                    {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, {}, Modifier.fillMaxSize().padding(24.dp))
                                else Box(Modifier.padding(64.dp)) { V2NetworkPanel(backdrop, sample) }
                            }
                        }
                    }
                    try {
                        repeat(4) { scene.render(it * 16_666_667L).close() }
                        val file = output.resolve("$page-$width-${if (darkText) "light" else "dark"}-${(fontScale * 100).toInt()}.png")
                        scene.render(83_333_335L).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
                        }
                        assertTrue(file.length() > 10000)
                    } finally { scene.close() }
                }
            }
            }
            }
        }
    }
}
