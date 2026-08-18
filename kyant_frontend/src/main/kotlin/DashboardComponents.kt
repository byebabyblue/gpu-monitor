import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

internal val V2Background = Color(0xFF070C17)
internal val V2Accent = Color(0xFF91B9FF)
internal val V2AccentStrong = Color(0xFF64A0FF)
internal val V2Green = Color(0xFF61E4A6)
internal val V2Yellow = Color(0xFFFFC76A)
internal val V2Red = Color(0xFFFF778D)
internal val V2Cyan = Color(0xFF66DFE8)
internal val LocalV2ReadabilityBlur = staticCompositionLocalOf { false }
internal val LocalV2DarkText = staticCompositionLocalOf { false }
internal val LocalV2TopBarBlur = staticCompositionLocalOf { true }
internal val LocalV2BottomBarBlur = staticCompositionLocalOf { true }

internal val V2Text: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalV2DarkText.current) Color(0xFF111827) else Color(0xFFF4F7FF)

internal val V2Dim: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalV2DarkText.current) Color(0xFF374151) else Color(0xFFDCE5F4)

internal val V2Muted: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalV2DarkText.current) Color(0xFF647083) else Color(0xFFC3D0E5)

private val chartColors = listOf(
    Color(0xFF72A8FF),
    Color(0xFF63E0B0),
    Color(0xFFFFC568),
    Color(0xFFFF7E96),
    Color(0xFFC18BFF),
    Color(0xFF57D7E6),
    Color(0xFFFF9E64),
    Color(0xFF9ADB70),
)

@Composable
internal fun V2Backdrop(backdrop: LayerBackdrop, wallpaperPath: String) {
    val readabilityBlur = LocalV2ReadabilityBlur.current
    val darkText = LocalV2DarkText.current
    val wallpaper = remember(wallpaperPath) {
        if (wallpaperPath.isBlank()) null
        else runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(File(wallpaperPath).readBytes()).toComposeImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            if (wallpaper == null) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = if (darkText) {
                            listOf(Color(0xFFE5EDF8), Color(0xFFD7E2F1), Color(0xFFF0E5F7))
                        } else {
                            listOf(Color(0xFF11233F), V2Background, Color(0xFF20122F))
                        },
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                )
                drawCircle(
                    color = Color(0xFF4E84FF).copy(alpha = if (darkText) 0.13f else 0.21f),
                    radius = size.minDimension * 0.39f,
                    center = Offset(size.width * 0.18f, size.height * 0.16f),
                )
                drawCircle(
                    color = Color(0xFFB765FF).copy(alpha = if (darkText) 0.10f else 0.16f),
                    radius = size.minDimension * 0.34f,
                    center = Offset(size.width * 0.88f, size.height * 0.80f),
                )
                drawCircle(
                    color = Color(0xFF35E4BB).copy(alpha = if (darkText) 0.07f else 0.08f),
                    radius = size.minDimension * 0.21f,
                    center = Offset(size.width * 0.62f, size.height * 0.18f),
                )
            } else {
                drawRect(
                    if (darkText) {
                        Color.White.copy(alpha = if (readabilityBlur) 0.30f else 0.08f)
                    } else {
                        Color.Black.copy(alpha = if (readabilityBlur) 0.30f else 0.08f)
                    }
                )
            }
        }
    }
}

@Composable
internal fun V2GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    tintColor: Color? = null,
    tintAlpha: Float = 0.25f,
    blurRadius: Dp = 14.dp,
    lensRadius: Dp = 29.dp,
    enableBlur: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val darkText = LocalV2DarkText.current
    val surfaceBlur = enableBlur ?: LocalV2ReadabilityBlur.current
    val surfaceTint = tintColor ?: if (darkText) Color.White else Color(0xFF0C162C)
    Box(
        modifier = modifier
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (surfaceBlur) blur(blurRadius.toPx())
                    lens(lensRadius.toPx(), (lensRadius.value * 0.72f).dp.toPx())
                },
                highlight = { Highlight.Ambient },
                onDrawSurface = {
                    drawRect(surfaceTint.copy(alpha = tintAlpha))
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.105f),
                                Color(0xFF8DB7FF).copy(alpha = 0.055f),
                                Color.Transparent,
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.045f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.075f),
                            )
                        )
                    )
                },
            ),
        content = { content() },
    )
}

@Composable
internal fun V2PureGlassSurface(
    backdrop: Backdrop,
    modifier: Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
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
                    lens(36.dp.toPx(), 25.dp.toPx())
                },
                highlight = { Highlight.Ambient },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.012f)) },
            ),
        content = { content() },
    )
}

@Composable
internal fun V2Header(
    backdrop: Backdrop,
    page: V2Tab,
    apiOnline: Boolean,
    apiMessage: String,
    onRefresh: () -> Unit,
) {
    val topBarBlur = LocalV2TopBarBlur.current
    V2GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(32.dp),
        tintColor = Color.White,
        tintAlpha = 0.055f,
        blurRadius = 13.dp,
        lensRadius = 38.dp,
        enableBlur = topBarBlur,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 23.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = V2AccentStrong,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column {
                Text(page.label, color = V2Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(page.subtitle, color = V2Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            V2StatusPill(apiOnline, apiMessage, onRefresh)
        }
    }
}

@Composable
private fun V2StatusPill(online: Boolean, message: String, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onRefresh)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = if (online) V2Green else V2Yellow, fontSize = 10.sp)
        Spacer(Modifier.width(7.dp))
        Text(message, color = V2Dim, fontSize = 11.sp)
    }
}

@Composable
internal fun V2BottomBar(
    backdrop: Backdrop,
    selectedTab: V2Tab,
    onSelected: (V2Tab) -> Unit,
) {
    val bottomBarBlur = LocalV2BottomBarBlur.current
    val clickBlocker = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 110.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        V2GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().height(76.dp),
            shape = RoundedCornerShape(38.dp),
            tintColor = Color.White,
            tintAlpha = 0.055f,
            blurRadius = 13.dp,
            lensRadius = 40.dp,
            enableBlur = bottomBarBlur,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = clickBlocker,
                        indication = null,
                        onClick = {},
                    )
                )
                BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
                val tabs = V2Tab.entries
                val itemWidth = maxWidth / tabs.size.toFloat()
                val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
                val sliderOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex.toFloat(),
                    animationSpec = spring(dampingRatio = 0.76f, stiffness = 330f),
                    label = "v2-bottom-slider",
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
                                if (bottomBarBlur) blur(10.dp.toPx())
                                lens(33.dp.toPx(), 23.dp.toPx())
                            },
                            highlight = { Highlight.Ambient },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.075f))
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.11f),
                                            Color.White.copy(alpha = 0.02f),
                                            Color.Transparent,
                                        ),
                                        center = Offset(size.width * 0.24f, 0f),
                                        radius = size.width * 0.92f,
                                    )
                                )
                            },
                    )
                )
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { tab ->
                        val selected = tab == selectedTab
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(30.dp))
                                .clickable { onSelected(tab) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) V2Text else V2Dim,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                tab.label,
                                color = if (selected) V2Text else V2Dim,
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
}

@Composable
internal fun V2SegmentedSlider(
    backdrop: Backdrop,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
) {
    if (labels.isEmpty()) return
    val readabilityBlur = LocalV2ReadabilityBlur.current
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    val shape = RoundedCornerShape(height / 2)
    BoxWithConstraints(
        modifier = modifier.height(height).clip(shape)
            .background(Color.White.copy(alpha = 0.045f)),
    ) {
        val itemWidth = maxWidth / labels.size.toFloat()
        val sliderOffset by animateDpAsState(
            targetValue = itemWidth * safeIndex.toFloat(),
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 360f),
            label = "v2-segment-slider",
        )
        Box(
            Modifier.offset(x = sliderOffset).width(itemWidth).fillMaxHeight()
                .clip(shape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        if (readabilityBlur) blur(8.dp.toPx())
                        lens(24.dp.toPx(), 17.dp.toPx())
                    },
                    highlight = { Highlight.Ambient },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.085f))
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.12f),
                                    Color.White.copy(alpha = 0.025f),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.26f, 0f),
                                radius = size.width,
                            )
                        )
                    },
                )
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (index == safeIndex) V2Text else V2Dim,
                        fontSize = 10.sp,
                        fontWeight = if (index == safeIndex) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun V2SummaryChip(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(title, color = V2Muted, fontSize = 10.sp)
        Text(value, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun V2MetricRing(
    title: String,
    value: Double,
    subtitle: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2f + 3.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(
                    Color.White.copy(alpha = 0.095f), -90f, 360f, false,
                    Offset(inset, inset), arcSize, style = Stroke(stroke),
                )
                drawArc(
                    v2UsageColor(value), -90f,
                    value.coerceIn(0.0, 100.0).toFloat() * 3.6f, false,
                    Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text("${value.roundToInt()}%", color = V2Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, color = V2Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            color = V2Muted,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun V2UsageBar(label: String, value: Double, color: Color = v2UsageColor(value)) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = V2Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("${value.roundToInt()}%", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.fillMaxWidth().height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.07f))
        ) {
            Box(
                Modifier.fillMaxWidth(value.coerceIn(0.0, 100.0).toFloat() / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
internal fun V2ServerSidebar(
    backdrop: Backdrop,
    servers: List<MonitorServer>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val readabilityBlur = LocalV2ReadabilityBlur.current
    val itemHeight = 66.dp
    val itemSpacing = 8.dp
    val sliderOffset by animateDpAsState(
        targetValue = (itemHeight + itemSpacing) * selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 330f),
        label = "v2-server-slider",
    )
    val sliderShape = RoundedCornerShape(17.dp)
    val scroll = rememberScrollState()
    LaunchedEffect(selectedIndex) {
        val approximate = selectedIndex * 74
        if (approximate < scroll.value || approximate > scroll.value + 420) {
            scroll.animateScrollTo(max(0, approximate - 110))
        }
    }
    V2GlassSurface(backdrop, modifier, RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("SERVERS", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                Box(
                    Modifier.offset(y = sliderOffset).fillMaxWidth().height(itemHeight)
                        .clip(sliderShape)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { sliderShape },
                            effects = {
                                vibrancy()
                                if (readabilityBlur) blur(9.dp.toPx())
                                lens(27.dp.toPx(), 19.dp.toPx())
                            },
                            highlight = { Highlight.Ambient },
                            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.085f)) },
                        )
                )
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                    servers.forEachIndexed { index, server ->
                        Column(
                            Modifier.fillMaxWidth().height(itemHeight)
                                .clip(RoundedCornerShape(17.dp))
                                .clickable { onSelected(index) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "●",
                                    color = when {
                                        server.isShowcase -> V2AccentStrong
                                        server.connected -> V2Green
                                        else -> V2Red
                                    },
                                    fontSize = 10.sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    server.name,
                                    color = V2Text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                if (server.isShowcase) "MOCK · 24H HISTORY"
                                else "GPU ${server.averageGpuPercent.roundToInt()}% · ${server.gpus.size} DEVICES",
                                color = if (index == selectedIndex) V2Accent else V2Dim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun V2GpuLineChart(
    backdrop: Backdrop,
    history: List<GpuHistorySeries>,
    hours: Int,
    modifier: Modifier = Modifier,
) {
    val cutoff = System.currentTimeMillis() / 1000.0 - hours * 3600.0
    val visible = remember(history, hours) {
        history.map { series -> series.copy(points = series.points.filter { it.timestampSeconds >= cutoff }) }
    }
    V2GlassSurface(backdrop, modifier, RoundedCornerShape(22.dp), tintAlpha = 0.20f) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("GPU USAGE HISTORY", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Last $hours hours · utilization %", color = V2Dim, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    visible.take(8).forEachIndexed { index, series ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(chartColors[index % chartColors.size]))
                            Spacer(Modifier.width(4.dp))
                            Text("${series.index}", color = V2Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Canvas(Modifier.fillMaxSize()) {
                    val left = 8.dp.toPx()
                    val top = 8.dp.toPx()
                    val right = size.width - 5.dp.toPx()
                    val bottom = size.height - 8.dp.toPx()
                    for (step in 0..4) {
                        val y = top + (bottom - top) * step / 4f
                        drawLine(
                            Color.White.copy(alpha = 0.075f),
                            Offset(left, y), Offset(right, y), 1.dp.toPx(),
                        )
                    }
                    val allPoints = visible.flatMap { it.points }
                    if (allPoints.isNotEmpty()) {
                        val minTime = allPoints.minOf { it.timestampSeconds }
                        val maxTime = max(minTime + 1.0, allPoints.maxOf { it.timestampSeconds })
                        visible.take(8).forEachIndexed { index, series ->
                            if (series.points.size < 2) return@forEachIndexed
                            val path = Path()
                            series.points.forEachIndexed { pointIndex, point ->
                                val x = left + ((point.timestampSeconds - minTime) / (maxTime - minTime)).toFloat() * (right - left)
                                val y = bottom - (point.value.coerceIn(0.0, 100.0).toFloat() / 100f) * (bottom - top)
                                if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path,
                                color = chartColors[index % chartColors.size],
                                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                if (visible.none { it.points.size >= 2 }) {
                    Text(
                        "Collecting history…",
                        color = V2Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("-${hours}h", color = V2Muted, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("now", color = V2Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
internal fun V2GpuHeatmap(
    backdrop: Backdrop,
    history: List<GpuHistorySeries>,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(history) { hourlyMatrix(history) }
    V2GlassSurface(backdrop, modifier, RoundedCornerShape(22.dp), tintAlpha = 0.19f) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("HOURLY GPU LOAD", color = V2Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("One Canvas · glass-like cells without per-cell blur cost", color = V2Dim, fontSize = 10.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("idle", color = V2Muted, fontSize = 9.sp)
                    listOf(15.0, 45.0, 75.0, 95.0).forEach { value ->
                        Box(
                            Modifier.size(11.dp).clip(RoundedCornerShape(3.dp))
                                .background(heatColor(value))
                        )
                    }
                    Text("busy", color = V2Muted, fontSize = 9.sp)
                }
            }
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val rows = max(1, matrix.size)
                val columns = 24
                val gap = 3.dp.toPx()
                val labelWidth = 36.dp.toPx()
                val cellWidth = (size.width - labelWidth - gap * (columns - 1)) / columns
                val cellHeight = (size.height - gap * (rows - 1)) / rows
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val value = matrix.getOrNull(row)?.getOrNull(column) ?: Double.NaN
                        val x = labelWidth + column * (cellWidth + gap)
                        val y = row * (cellHeight + gap)
                        val color = if (value.isNaN()) Color.White.copy(alpha = 0.035f) else heatColor(value)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(cellWidth.coerceAtLeast(1f), cellHeight.coerceAtLeast(1f)),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        )
                        drawLine(
                            Color.White.copy(alpha = if (value.isNaN()) 0.025f else 0.10f),
                            Offset(x + 2.dp.toPx(), y + 1.dp.toPx()),
                            Offset(x + cellWidth - 2.dp.toPx(), y + 1.dp.toPx()),
                            0.7.dp.toPx(),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
                Text("24h ago", color = V2Muted, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("12h", color = V2Muted, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("now", color = V2Muted, fontSize = 9.sp)
            }
        }
    }
}

private fun hourlyMatrix(history: List<GpuHistorySeries>): List<List<Double>> {
    if (history.isEmpty()) return emptyList()
    val endHour = floor(System.currentTimeMillis() / 1000.0 / 3600.0) * 3600.0 + 3600.0
    val startHour = endHour - 24 * 3600.0
    return history.take(8).map { series ->
        val buckets = Array(24) { mutableListOf<Double>() }
        series.points.forEach { point ->
            val index = ((point.timestampSeconds - startHour) / 3600.0).toInt()
            if (index in 0..23) buckets[index].add(point.value)
        }
        buckets.map { values -> if (values.isEmpty()) Double.NaN else values.average() }
    }
}

private fun heatColor(value: Double): Color {
    val fraction = value.coerceIn(0.0, 100.0).toFloat() / 100f
    val base = when {
        value >= 85.0 -> V2Red
        value >= 60.0 -> V2Yellow
        value >= 30.0 -> V2Cyan
        else -> V2AccentStrong
    }
    return base.copy(alpha = 0.16f + fraction * 0.72f)
}

internal fun v2UsageColor(value: Double): Color = when {
    value >= 85.0 -> V2Red
    value >= 60.0 -> V2Yellow
    else -> V2Green
}
