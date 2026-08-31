import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "local.gpu.monitor"
version = "1.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Kyant's published Compose Multiplatform liquid-glass renderer.
    implementation("io.github.kyant0:backdrop:2.0.0")

    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Xerver"
            packageVersion = "1.1.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            windows {
                shortcut = true
                menuGroup = "Xerver"
                perUserInstall = true
                upgradeUuid = "BCDAABCC-6CD7-3BB2-8B4A-16FFFDA2D8EC"
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
            }
        }
    }
}

fun verifyBackendDirectory(directory: File) {
    val verifier = rootDir.parentFile.resolve("tools/verify_backend_bundle.ps1")
    val shell = if (System.getProperty("os.name").startsWith("Windows")) "powershell.exe" else "pwsh"
    val validation = providers.exec {
        commandLine(shell, "-NoProfile", "-NonInteractive", "-File", verifier.absolutePath,
            "-BackendDirectory", directory.absolutePath, "-Json")
        isIgnoreExitValue = true
    }
    val details = validation.standardOutput.asText.get() + validation.standardError.asText.get()
    if (validation.result.get().exitValue != 0) {
        throw GradleException("Incomplete or modified backend bundle. Rebuild with tools/build_backend.ps1.\n$details")
    }
    logger.lifecycle(details.trim())
}

val verifyBundledBackend = tasks.register("verifyBundledBackend") {
    val bundledBackend = layout.projectDirectory.dir("appResources/common/backend")
    inputs.dir(bundledBackend)
    doLast { verifyBackendDirectory(bundledBackend.asFile) }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(verifyBundledBackend)
}

val verifyPreparedBackend = tasks.register("verifyPreparedBackend") {
    dependsOn("prepareAppResources")
    val preparedBackend = layout.buildDirectory.dir(
        "compose/tmp/prepareAppResources/backend",
    )
    doLast { verifyBackendDirectory(preparedBackend.get().asFile) }
}

tasks.matching {
    it.name in setOf("createDistributable", "packageExe", "packageMsi", "packageDistributionForCurrentOS")
}.configureEach {
    dependsOn("test", verifyBundledBackend, verifyPreparedBackend)
}
