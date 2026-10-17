import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
            for (darkText in listOf(false, true)) {
                for (page in listOf("gpu", "network")) {
                    val scene = ImageComposeScene(width = 1360, height = 1200, density = Density(1f)) {
                        CompositionLocalProvider(LocalV2DarkText provides darkText, LocalV2CardBlurLevel provides BlurLevelMedium) {
                            val backdrop = rememberLayerBackdrop()
                            val sample = showcaseMonitorServer().copy(gpus = showcaseMonitorServer().gpus.take(2),
                                networkCheckedAt = System.currentTimeMillis() / 1000.0,
                                network = listOf(
                                    NetworkProbe("百度", "www.baidu.com", 18.0, "ok", 83.0, "ok", 200),
                                    NetworkProbe("GitHub", "github.com", null, "timeout", 236.0, "ok", 200),
                                    NetworkProbe("清华 PyPI 源", "pypi.tuna.tsinghua.edu.cn", 25.0, "ok", 98.0, "ok", 200),
                                    NetworkProbe("PyTorch 官方源", "download.pytorch.org", null, "timeout", null, "failed", 0),
                                ))
                            Box(Modifier.fillMaxSize()) {
                                V2Backdrop(backdrop, "")
                                if (page == "gpu") V2GpuPage(backdrop, listOf(sample), 0, showcaseHistory().take(2), 1440, {}, {}, Modifier.fillMaxSize().padding(24.dp))
                                else Box(Modifier.padding(64.dp)) { V2NetworkPanel(backdrop, sample) }
                            }
                        }
                    }
                    try {
                        repeat(4) { scene.render(it * 16_666_667L).close() }
                        val file = output.resolve("$page-${if (darkText) "light" else "dark"}.png")
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
