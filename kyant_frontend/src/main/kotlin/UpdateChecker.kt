import com.google.gson.JsonParser
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI

internal const val GpuMonitorVersion = "0.2.2"
internal const val GpuMonitorRepositoryUrl = "https://github.com/byebabyblue/gpu-monitor"
internal const val GpuMonitorReleasesUrl = "$GpuMonitorRepositoryUrl/releases"

private const val LatestReleaseApiUrl =
    "https://api.github.com/repos/byebabyblue/gpu-monitor/releases/latest"

internal data class GpuMonitorRelease(
    val tagName: String,
    val displayName: String,
    val pageUrl: String,
    val installerUrl: String?,
    val notes: String,
    val updateAvailable: Boolean,
)

internal object UpdateChecker {
    fun fetchLatest(): Result<GpuMonitorRelease> = runCatching {
        val connection = URI.create(LatestReleaseApiUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "GPU-Monitor/$GpuMonitorVersion")

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val message = if (status == 404) "仓库还没有已发布的 Release" else "GitHub 返回 HTTP $status"
                error(message)
            }

            val json = JsonParser.parseString(response).asJsonObject
            val tagName = json.get("tag_name")?.asString?.trim().orEmpty()
            require(tagName.isNotBlank()) { "最新 Release 没有版本号" }
            val pageUrl = json.get("html_url")?.asString?.trim().orEmpty()
                .ifBlank { "$GpuMonitorReleasesUrl/latest" }
            val displayName = json.get("name")?.asString?.trim().orEmpty().ifBlank { tagName }
            val notes = plainTextReleaseNotes(
                json.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            )
            val installerUrl = json.getAsJsonArray("assets")
                ?.asSequence()
                ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
                ?.firstOrNull { asset ->
                    asset.get("name")?.asString?.lowercase()?.endsWith(".exe") == true
                }
                ?.get("browser_download_url")
                ?.asString
                ?.trim()
                ?.takeIf(String::isNotBlank)

            GpuMonitorRelease(
                tagName = tagName,
                displayName = displayName,
                pageUrl = pageUrl,
                installerUrl = installerUrl,
                notes = notes,
                updateAvailable = compareVersions(tagName, GpuMonitorVersion) > 0,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun openInBrowser(url: String): Result<Unit> = runCatching {
        val uri = URI.create(url)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && (host == "github.com" || host.endsWith(".github.com"))) {
            "更新地址不是受信任的 GitHub 链接"
        }
        check(Desktop.isDesktopSupported()) { "当前系统无法打开浏览器" }
        Desktop.getDesktop().browse(uri)
    }

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val length = maxOf(leftParts.size, rightParts.size, 3)
        for (index in 0 until length) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    internal fun plainTextReleaseNotes(markdown: String): String {
        val image = Regex("!\\[([^]]*)]\\([^)]+\\)")
        val link = Regex("\\[([^]]+)]\\([^)]+\\)")
        val heading = Regex("^\\s{0,3}#{1,6}\\s*")
        val quote = Regex("^\\s*>+\\s*")
        val bullet = Regex("^\\s*[-+*]\\s+")
        val numbered = Regex("^\\s*\\d+[.)]\\s+")
        val html = Regex("<[^>]+>")
        val horizontalRule = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")

        return markdown.lineSequence()
            .map { source ->
                if (horizontalRule.matches(source)) return@map ""
                source
                    .replace(image) { match -> match.groupValues[1] }
                    .replace(link) { match -> match.groupValues[1] }
                    .replace(heading, "")
                    .replace(quote, "")
                    .replace(bullet, "• ")
                    .replace(numbered, "• ")
                    .replace(html, "")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
                    .trimEnd()
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun versionParts(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
