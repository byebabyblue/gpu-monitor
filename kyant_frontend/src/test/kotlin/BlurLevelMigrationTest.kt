import kotlin.test.Test
import kotlin.test.assertEquals

class BlurLevelMigrationTest {
    @Test
    fun legacyEnabledBlurMigratesToMedium() {
        assertEquals(BlurLevelMedium, resolveBlurLevel(storedLevel = null, legacyEnabled = true))
        assertEquals(BlurLevelOff, resolveBlurLevel(storedLevel = null, legacyEnabled = false))
    }

    @Test
    fun storedBlurLevelWinsAndIsClamped() {
        assertEquals(BlurLevelLight, resolveBlurLevel(storedLevel = BlurLevelLight, legacyEnabled = false))
        assertEquals(BlurLevelHeavy, resolveBlurLevel(storedLevel = 99, legacyEnabled = false))
        assertEquals(BlurLevelOff, resolveBlurLevel(storedLevel = -1, legacyEnabled = true))
    }

    @Test
    fun blurStrengthIncreasesByLevel() {
        assertEquals(0f, blurScaleForLevel(BlurLevelOff))
        assertEquals(0.55f, blurScaleForLevel(BlurLevelLight))
        assertEquals(1f, blurScaleForLevel(BlurLevelMedium))
        assertEquals(1.45f, blurScaleForLevel(BlurLevelHeavy))
    }
}
