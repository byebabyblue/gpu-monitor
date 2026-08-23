import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WallpaperManagerTest {
    @Test
    fun keepsOriginalAndCreatesDisplaySizedCopy() {
        val temporaryRoot = Files.createTempDirectory("gpu-monitor-wallpaper-test").toFile()
        val source = temporaryRoot.resolve("source.png")
        try {
            val image = BufferedImage(2600, 1400, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(image, "png", source)
            System.setProperty("gpu.monitor.wallpaper.root", temporaryRoot.resolve("managed").absolutePath)

            val asset = WallpaperManager.importWallpaper(source.absolutePath).getOrThrow()
            val original = java.io.File(asset.originalPath)
            val display = java.io.File(asset.displayPath)
            val target = WallpaperManager.displayResolutionLabel().split(" × ").map(String::toInt)
            val displayImage = ImageIO.read(display)

            assertTrue(original.isFile)
            assertTrue(display.isFile)
            assertEquals("original", original.parentFile.name)
            assertEquals("display", display.parentFile.name)
            assertTrue(displayImage.width <= target[0])
            assertTrue(displayImage.height <= target[1])
            assertTrue(displayImage.width <= image.width)
            assertTrue(displayImage.height <= image.height)
            assertEquals(asset.displayPath, WallpaperManager.normalizeConfiguredPath(asset.displayPath).getOrThrow())
        } finally {
            System.clearProperty("gpu.monitor.wallpaper.root")
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun migratesTheLegacyOriginalInsteadOfReusingItsDownscaledDisplayCopy() {
        val temporaryRoot = Files.createTempDirectory("xerver-wallpaper-migration-test").toFile()
        val managedRoot = temporaryRoot.resolve("managed")
        val legacyRoot = temporaryRoot.resolve("legacy")
        val legacyOriginal = legacyRoot.resolve("original/legacyhash.png")
        val legacyDisplay = legacyRoot.resolve("display/legacyhash-1920x1080.png")
        try {
            legacyOriginal.parentFile.mkdirs()
            legacyDisplay.parentFile.mkdirs()
            ImageIO.write(BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), "png", legacyOriginal)
            ImageIO.write(BufferedImage(80, 45, BufferedImage.TYPE_INT_RGB), "png", legacyDisplay)
            System.setProperty("gpu.monitor.wallpaper.root", managedRoot.absolutePath)
            System.setProperty("gpu.monitor.legacy.wallpaper.root", legacyRoot.absolutePath)

            val asset = WallpaperManager.importWallpaper(legacyDisplay.absolutePath).getOrThrow()
            val migratedOriginal = ImageIO.read(java.io.File(asset.originalPath))

            assertEquals(320, migratedOriginal.width)
            assertEquals(180, migratedOriginal.height)
            assertTrue(java.io.File(asset.displayPath).canonicalPath.startsWith(managedRoot.canonicalPath))
            assertTrue(legacyOriginal.isFile, "Migration must not remove the user's previous wallpaper")
        } finally {
            System.clearProperty("gpu.monitor.wallpaper.root")
            System.clearProperty("gpu.monitor.legacy.wallpaper.root")
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun releaseNotesAreConvertedToReadablePlainText() {
        val notes = UpdateChecker.plainTextReleaseNotes(
            """
            ## 修复内容
            - **壁纸**会保留原图
            - 读取 `[config.json](https://example.com)`，版本为 `v0.2.2`
            """.trimIndent(),
        )

        assertTrue("修复内容" in notes)
        assertTrue("• 壁纸会保留原图" in notes)
        assertTrue("config.json" in notes)
        assertFalse("**" in notes)
        assertFalse("`" in notes)
        assertFalse("](" in notes)
    }
}
