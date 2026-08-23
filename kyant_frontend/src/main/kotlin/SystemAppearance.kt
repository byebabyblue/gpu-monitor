import java.util.concurrent.TimeUnit

internal object SystemAppearance {
    private const val PersonalizeKey =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    fun appsUseLightTheme(): Boolean {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return false
        val process = runCatching {
            ProcessBuilder(
                "reg.exe",
                "query",
                PersonalizeKey,
                "/v",
                "AppsUseLightTheme",
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return false

        return try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                parseAppsUseLightTheme(output) ?: false
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal fun parseAppsUseLightTheme(registryOutput: String): Boolean? {
    val valueLine = registryOutput.lineSequence()
        .firstOrNull { it.contains("AppsUseLightTheme", ignoreCase = true) }
        ?: return null
    val hexadecimal = Regex("0x([0-9a-fA-F]+)").find(valueLine)?.groupValues?.getOrNull(1)
        ?: return null
    return hexadecimal.toLongOrNull(16)?.let { it != 0L }
}

internal fun textModeUsesDarkText(
    textMode: String,
    systemAppsUseLightTheme: Boolean,
): Boolean = when (textMode.lowercase()) {
    "system" -> systemAppsUseLightTheme
    // Legacy values describe the text color. Keeping them avoids reversing upgraded installs.
    "dark" -> true
    else -> false
}
