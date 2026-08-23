import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemAppearanceTest {
    @Test
    fun preservesLegacyTextColorValuesUnderTheNewThemeLabels() {
        assertTrue(textModeUsesDarkText("dark", systemAppsUseLightTheme = false))
        assertFalse(textModeUsesDarkText("light", systemAppsUseLightTheme = true))
    }

    @Test
    fun systemModeFollowsTheWindowsApplicationTheme() {
        assertTrue(textModeUsesDarkText("system", systemAppsUseLightTheme = true))
        assertFalse(textModeUsesDarkText("system", systemAppsUseLightTheme = false))
    }

    @Test
    fun parsesWindowsRegistryThemeValues() {
        assertEquals(
            true,
            parseAppsUseLightTheme("AppsUseLightTheme    REG_DWORD    0x1"),
        )
        assertEquals(
            false,
            parseAppsUseLightTheme("AppsUseLightTheme    REG_DWORD    0x0"),
        )
    }

    @Test
    fun exposesTheRequestedDiskRefreshChoices() {
        assertEquals(
            listOf(10, 60, 3600, 21600, 86400),
            V2DiskIntervalOptions.map { it.first },
        )
        assertEquals(
            listOf("10s", "60s", "1h", "6h", "24h"),
            V2DiskIntervalOptions.map { it.second },
        )
        assertEquals(60, normalizeDiskInterval(30))
        assertEquals(21600, PollSettings().diskSeconds)
    }

    @Test
    fun freshInstallsDefaultToTheSystemAppearance() {
        assertEquals("system", UiSettings().textMode)
        assertEquals(
            listOf("系统", "浅色", "深色"),
            V2TextModeOptions.map { it.second },
        )
    }
}
