import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupReadinessTest {
    @Test
    fun doesNotRevealBeforeConfigAndWallpaperAreReady() {
        assertFalse(
            isStartupAppearanceReady(
                configLoaded = false,
                configuredWallpaperPath = "wallpaper.png",
                readyWallpaperPath = "wallpaper.png",
                minimumDurationElapsed = true,
                systemAppearanceReady = true,
            ),
        )
        assertFalse(
            isStartupAppearanceReady(
                configLoaded = true,
                configuredWallpaperPath = "wallpaper.png",
                readyWallpaperPath = "old-wallpaper.png",
                minimumDurationElapsed = true,
                systemAppearanceReady = true,
            ),
        )
        assertFalse(
            isStartupAppearanceReady(
                configLoaded = true,
                configuredWallpaperPath = "wallpaper.png",
                readyWallpaperPath = "wallpaper.png",
                minimumDurationElapsed = false,
                systemAppearanceReady = true,
            ),
        )
        assertFalse(
            isStartupAppearanceReady(
                configLoaded = true,
                configuredWallpaperPath = "wallpaper.png",
                readyWallpaperPath = "wallpaper.png",
                minimumDurationElapsed = true,
                systemAppearanceReady = false,
            ),
        )
    }

    @Test
    fun revealsAfterTheCompleteAppearanceIsReady() {
        assertTrue(
            isStartupAppearanceReady(
                configLoaded = true,
                configuredWallpaperPath = "wallpaper.png",
                readyWallpaperPath = "wallpaper.png",
                minimumDurationElapsed = true,
                systemAppearanceReady = true,
            ),
        )
        assertTrue(
            isStartupAppearanceReady(
                configLoaded = true,
                configuredWallpaperPath = "",
                readyWallpaperPath = "",
                minimumDurationElapsed = true,
                systemAppearanceReady = true,
            ),
        )
    }
}
