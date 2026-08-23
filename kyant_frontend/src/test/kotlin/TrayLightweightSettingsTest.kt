import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrayLightweightSettingsTest {
    @Test
    fun defaultsKeepTrayLightweightModeDisabled() {
        val ui = UiSettings()

        assertFalse(ui.trayLightweightEnabled)
        assertEquals(DefaultTrayLightweightDelayMinutes, ui.trayLightweightDelayMinutes)
    }

    @Test
    fun delayIsClampedToSupportedConfigRange() {
        assertEquals(1, normalizeTrayLightweightDelayMinutes(0))
        assertEquals(60, normalizeTrayLightweightDelayMinutes(60))
        assertEquals(1440, normalizeTrayLightweightDelayMinutes(9999))
    }

    @Test
    fun settingsSliderUsesNearestDelayOption() {
        assertEquals(1, normalizeTrayLightweightDelayOption(2))
        assertEquals(10, normalizeTrayLightweightDelayOption(12))
        assertEquals(60, normalizeTrayLightweightDelayOption(55))
    }
}
