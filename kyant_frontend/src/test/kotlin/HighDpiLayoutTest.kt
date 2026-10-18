import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class HighDpiLayoutTest {
    @Test
    fun fullHdAtOneHundredPercentMatches4kAtTwoHundredPercent() {
        val fullHd = dashboardDensity(Density(density = 1f, fontScale = 1f), userFontScale = 1.08)
        val ultraHd = dashboardDensity(Density(density = 2f, fontScale = 1f), userFontScale = 1.08)

        assertEquals(1920f, 1920f / fullHd.density)
        assertEquals(1080f, 1080f / fullHd.density)
        assertEquals(1920f, 3840f / ultraHd.density)
        assertEquals(1080f, 2160f / ultraHd.density)
        assertEquals(fullHd.fontScale, ultraHd.fontScale)
        assertEquals(1f, fullHd.density)
        assertEquals(2f, ultraHd.density)
    }

    @Test
    fun defaultTextSizeMatchesFormer135Percent() {
        assertEquals(1.35, UiSettings().fontScale)
        assertEquals(1.35f, dashboardDensity(Density(1f, 1f), UiSettings().fontScale).fontScale)
    }

    @Test
    fun fontSettingDoesNotOverrideTheSystemDpi() {
        val density = dashboardDensity(Density(density = 2.5f, fontScale = 1.25f), userFontScale = 1.16)

        assertEquals(2.5f, density.density)
        assertEquals(1.45f, density.fontScale, absoluteTolerance = 0.0001f)
    }
}
