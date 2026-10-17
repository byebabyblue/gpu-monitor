import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

@Composable
internal fun V2NetworkPanel(backdrop: Backdrop, server: MonitorServer) {
    val darkText = LocalV2DarkText.current
    val good = if (darkText) Color(0xFF087D53) else V2Green
    val warning = if (darkText) Color(0xFF915B00) else V2Yellow
    val bad = if (darkText) Color(0xFFB51F43) else V2Red
    val age = (System.currentTimeMillis() / 1000.0 - server.networkCheckedAt).coerceAtLeast(0.0)
    val stale = !server.connected || age > 120
    V2GlassSurface(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("网络连通性", color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("从 ${server.name} 发起 · 每 60 秒检测 · HTTPS 包含 DNS / TLS", color = V2Dim, fontSize = 10.sp)
                }
                Text(
                    if (server.networkCheckedAt == 0.0) "等待首轮检测"
                    else if (stale) "历史结果 · ${age.toInt()} 秒前" else "${age.toInt()} 秒前",
                    color = if (stale) warning else good, fontSize = 10.sp,
                )
            }
            if (server.networkError.isNotBlank()) {
                Text(server.networkError, color = warning, fontSize = 12.sp)
            }
            if (server.network.isEmpty()) {
                Text(if (server.connected) "正在检测百度、GitHub、清华 PyPI 和 PyTorch 官方源…" else "服务器连接后开始检测", color = V2Muted, fontSize = 12.sp)
            }
            server.network.forEach { probe ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(V2Muted.copy(alpha = 0.07f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("●", color = if (stale) V2Muted else when (probe.httpsStatus) {
                        "ok" -> good
                        "unavailable" -> warning
                        else -> bad
                    }, fontSize = 12.sp)
                    Column(Modifier.weight(1f)) {
                        Text(probe.label, color = V2Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(probe.host, color = V2Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(when (probe.pingStatus) {
                            "ok" -> "Ping ${probe.pingMs?.roundToInt() ?: "—"} ms"
                            "timeout" -> "Ping 超时 / 被禁"
                            else -> "Ping 工具不可用"
                        }, color = V2Dim, fontSize = 11.sp)
                        Text(when (probe.httpsStatus) {
                            "ok" -> "HTTPS ${probe.httpsMs?.roundToInt() ?: "—"} ms"
                            "http_error" -> "HTTP ${probe.httpCode}"
                            "unavailable" -> "curl 不可用"
                            else -> "HTTPS 连接失败"
                        }, color = if (!stale && probe.httpsStatus == "ok") good else warning, fontSize = 11.sp)
                    }
                }
            }
            Text("Ping 超时不代表网站不可用；HTTPS 延迟测量的是一次请求耗时，非下载速度。", color = V2Muted, fontSize = 10.sp)
        }
    }
}
