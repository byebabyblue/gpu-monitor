import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledBackendStartupTest {
    @Test
    fun startsARealChildWithClosedStdinAndCapturedOutput() = withInstallation { root ->
        var child: Process? = null
        val controller = BundledBackendController { builder ->
            assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput())
            assertEquals("1", builder.environment()["PYTHONUNBUFFERED"])
            assertEquals(root.resolve("Xerver").canonicalFile, builder.directory().canonicalFile)
            assertTrue(builder.command().contains("--backend-only"))
            assertTrue(builder.command().contains(root.resolve("Xerver/config.json").absolutePath))
            val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val java = File(System.getProperty("java.home"), "bin/$javaName")
            val classes = File(BackendStdinProbe::class.java.protectionDomain.codeSource.location.toURI())
            builder.command(java.absolutePath, "-cp", classes.absolutePath, "BackendStdinProbe")
            builder.start().also { child = it }
        }
        try {
            controller.startIfAvailable()
            val running = checkNotNull(child) { "Backend launch was skipped or failed before ProcessBuilder.start()" }
            assertTrue(running.waitFor(10, TimeUnit.SECONDS), "Backend must not block waiting for stdin")
            assertEquals(0, running.exitValue())
            val output = root.resolve("Xerver/logs/backend.log").readText()
            assertTrue(output.contains("backend-stdin-closed"))
            assertTrue(output.contains("backend-stderr-captured"))
            assertFalse(root.resolve("Xerver/logs/frontend.log").readText().contains("启动内置后端失败"))
        } finally {
            controller.stop()
            child?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    @Test
    fun processLaunchFailureIsLoggedAndCanBeRetried() = withInstallation { root ->
        var attempts = 0
        val controller = BundledBackendController {
            attempts++
            throw IOException("simulated process launch failure")
        }
        controller.startIfAvailable()
        controller.startIfAvailable()
        assertEquals(2, attempts)
        val log = root.resolve("Xerver/logs/frontend.log").readText()
        assertTrue(log.contains("java.io.IOException: simulated process launch failure"))
        assertTrue(log.contains("界面仍可使用并重试"))
    }

    @Test
    fun missingBackendIsLoggedWithoutEscapingToCompose() = withInstallation { root ->
        root.resolve("resources/backend/gpu-monitor-backend.exe").delete()
        var launched = false
        BundledBackendController {
            launched = true
            error("Must not launch a missing backend")
        }.startIfAvailable()
        assertFalse(launched)
        assertTrue(root.resolve("Xerver/logs/frontend.log").readText().contains("未找到内置后端"))
    }

    @Test
    fun setupFailureBeforeProcessBuilderUsesFallbackLogAndDoesNotCrash() = withInstallation { root ->
        root.resolve("Xerver").deleteRecursively()
        root.resolve("Xerver").writeText("This file deliberately blocks creation of the config directory.")
        var launched = false
        BundledBackendController {
            launched = true
            error("Configuration setup should fail first")
        }.startIfAvailable()
        assertFalse(launched)
        val log = root.resolve("temp/Xerver/logs/frontend.log").readText()
        assertTrue(log.contains("启动内置后端失败"))
        assertTrue(log.contains("Exception"))
    }

    @Test
    fun diagnosticsPreservePreviousLogAndNeverThrowWhenBothDirectoriesAreBlocked() = withInstallation { root ->
        val log = root.resolve("Xerver/logs/frontend.log")
        log.parentFile.mkdirs()
        log.writeText("x".repeat(4 * 1024 * 1024 + 1))
        StartupDiagnostics.log("after rotation")
        assertTrue(root.resolve("Xerver/logs/frontend.previous.log").length() > 4 * 1024 * 1024)
        assertTrue(log.readText().contains("after rotation"))
        root.resolve("Xerver").deleteRecursively()
        root.resolve("Xerver").writeText("blocked")
        root.resolve("temp").writeText("blocked")
        StartupDiagnostics.logException("both paths blocked", IOException("must not escape"))
    }

    private fun withInstallation(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xerver backend 启动测试 ").toFile()
        val properties = listOf("xerver.appdata.root", "compose.application.resources.dir", "java.io.tmpdir")
        val previous = properties.associateWith { System.getProperty(it) }
        try {
            System.setProperty("xerver.appdata.root", root.absolutePath)
            System.setProperty("compose.application.resources.dir", root.resolve("resources").absolutePath)
            System.setProperty("java.io.tmpdir", root.resolve("temp").absolutePath)
            val port = ServerSocket(0).use { it.localPort }
            val config = root.resolve("Xerver/config.json")
            config.parentFile.mkdirs()
            config.writeText("""{"settings":{"api_port":$port},"ui":{},"servers":[]}""")
            val backend = root.resolve("resources/backend/gpu-monitor-backend.exe")
            backend.parentFile.mkdirs()
            backend.writeText("The test launch factory replaces this placeholder with the Java probe.")
            block(root)
        } finally {
            previous.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
            root.deleteRecursively()
        }
    }
}
