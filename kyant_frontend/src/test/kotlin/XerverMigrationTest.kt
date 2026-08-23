import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XerverMigrationTest {
    @Test
    fun migratesLegacyConfigurationWithoutDeletingTheOriginal() {
        val temporaryRoot = Files.createTempDirectory("xerver-config-migration-test").toFile()
        val legacyDirectory = temporaryRoot.resolve("GPU Monitor").apply { mkdirs() }
        val legacyConfig = legacyDirectory.resolve("config.json")
        val legacyKnownHosts = legacyDirectory.resolve("ssh_known_hosts")
        val legacyBackup = legacyDirectory.resolve("config.backup.json")
        try {
            legacyConfig.writeText(
                """{"settings":{"api_port":9123},"ui":{"wallpaper_path":""},"servers":[]}""",
                StandardCharsets.UTF_8,
            )
            legacyKnownHosts.writeText("example host key", StandardCharsets.UTF_8)
            legacyBackup.writeText("{}", StandardCharsets.UTF_8)
            System.setProperty("xerver.appdata.root", temporaryRoot.absolutePath)

            assertEquals(9123, MonitorInstallation.configuredApiPort())
            val migrated = MonitorInstallation.ensureConfigFile()

            assertEquals(temporaryRoot.resolve("Xerver/config.json").canonicalPath, migrated.canonicalPath)
            assertEquals(legacyConfig.readText(), migrated.readText())
            assertEquals("example host key", migrated.parentFile.resolve("ssh_known_hosts").readText())
            assertTrue(migrated.parentFile.resolve("config.backup.json").isFile)
            assertTrue(legacyConfig.isFile, "Migration must leave the previous installation data untouched")
        } finally {
            System.clearProperty("xerver.appdata.root")
            temporaryRoot.deleteRecursively()
        }
    }
}
