import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt

internal data class ManagedWallpaper(
    val originalPath: String,
    val displayPath: String,
    val renderedWidth: Int,
    val renderedHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

internal object WallpaperManager {
    private const val AppDirectoryName = "GPU Monitor"

    fun normalizeConfiguredPath(path: String): Result<String> {
        if (path.isBlank()) return Result.success("")
        return importWallpaper(path).map { it.displayPath }
    }

    fun importWallpaper(path: String): Result<ManagedWallpaper> = runCatching {
        val target = largestDisplayResolution()
        val originalDirectory = originalDirectory().apply { mkdirs() }
        val displayDirectory = displayDirectory().apply { mkdirs() }
        val configured = File(path).absoluteFile
        val displayRoot = displayDirectory.toPath().toAbsolutePath().normalize()
        val configuredPath = configured.toPath().toAbsolutePath().normalize()
        val expectedSuffix = "-${target.width}x${target.height}.png"

        if (configured.isFile && configuredPath.startsWith(displayRoot) && configured.name.endsWith(expectedSuffix)) {
            val hash = configured.name.substringBefore('-')
            val original = findOriginal(originalDirectory, hash)
            return@runCatching ManagedWallpaper(
                originalPath = original?.absolutePath.orEmpty(),
                displayPath = configured.absolutePath,
                renderedWidth = target.width,
                renderedHeight = target.height,
                screenWidth = target.width,
                screenHeight = target.height,
            )
        }

        val source = if (configuredPath.startsWith(displayRoot)) {
            val hash = configured.name.substringBefore('-')
            findOriginal(originalDirectory, hash) ?: configured
        } else {
            configured
        }
        require(source.isFile) { "找不到壁纸文件：${source.absolutePath}" }

        val hash = sha256(source)
        val existingOriginal = findOriginal(originalDirectory, hash)
        val extension = source.extension.lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "image"
        val original = existingOriginal ?: File(originalDirectory, "$hash.$extension")
        if (!original.isFile) {
            Files.copy(source.toPath(), original.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }

        val display = File(displayDirectory, "$hash$expectedSuffix")
        if (!display.isFile) {
            createDisplayCopy(original, display, target)
        }
        val dimensions = imageDimensions(display)

        ManagedWallpaper(
            originalPath = original.absolutePath,
            displayPath = display.absolutePath,
            renderedWidth = dimensions.first,
            renderedHeight = dimensions.second,
            screenWidth = target.width,
            screenHeight = target.height,
        )
    }

    fun displayResolutionLabel(): String {
        val target = largestDisplayResolution()
        return "${target.width} × ${target.height}"
    }

    private fun createDisplayCopy(source: File, destination: File, target: DisplayResolution) {
        val encoded = Files.readAllBytes(source.toPath())
        Image.makeFromEncoded(encoded).use { sourceImage ->
            require(sourceImage.width > 0 && sourceImage.height > 0) { "无法读取壁纸尺寸" }
            val scale = minOf(
                1.0,
                target.width.toDouble() / sourceImage.width.toDouble(),
                target.height.toDouble() / sourceImage.height.toDouble(),
            )
            val width = (sourceImage.width * scale).roundToInt().coerceAtLeast(1)
            val height = (sourceImage.height * scale).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(width, height).use { surface ->
                val paint = Paint().apply { isAntiAlias = true }
                try {
                    surface.canvas.clear(0x00000000)
                    surface.canvas.drawImageRect(
                        sourceImage,
                        Rect.makeWH(sourceImage.width.toFloat(), sourceImage.height.toFloat()),
                        Rect.makeWH(width.toFloat(), height.toFloat()),
                        SamplingMode.MITCHELL,
                        paint,
                        true,
                    )
                    surface.makeImageSnapshot().use { outputImage ->
                        val encodedOutput = checkNotNull(outputImage.encodeToData(EncodedImageFormat.PNG, 100)) {
                            "无法生成优化壁纸"
                        }
                        encodedOutput.use { outputData ->
                            val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
                            try {
                                Files.write(temporary.toPath(), outputData.bytes)
                                moveReplacing(temporary, destination)
                            } finally {
                                Files.deleteIfExists(temporary.toPath())
                            }
                        }
                    }
                } finally {
                    paint.close()
                }
            }
        }
    }

    private fun imageDimensions(file: File): Pair<Int, Int> =
        Image.makeFromEncoded(Files.readAllBytes(file.toPath())).use { image -> image.width to image.height }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun findOriginal(directory: File, hash: String): File? =
        directory.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == hash }

    private fun wallpaperRoot(): File {
        System.getProperty("gpu.monitor.wallpaper.root")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.let { return it }
        val local = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
        return File(File(local, AppDirectoryName), "wallpapers")
    }

    private fun originalDirectory(): File = File(wallpaperRoot(), "original")

    private fun displayDirectory(): File = File(wallpaperRoot(), "display")

    private fun largestDisplayResolution(): DisplayResolution = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { device -> DisplayResolution(device.displayMode.width, device.displayMode.height) }
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
    }.getOrNull() ?: DisplayResolution(1920, 1080)

    private data class DisplayResolution(val width: Int, val height: Int)
}
