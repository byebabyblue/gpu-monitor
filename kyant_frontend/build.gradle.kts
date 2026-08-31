import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "local.gpu.monitor"
version = "1.0.1"

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
            packageVersion = "1.0.1"
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

val verifyBundledBackend = tasks.register("verifyBundledBackend") {
    val bundledBackend = layout.projectDirectory.file("appResources/common/backend/gpu-monitor-backend.exe")
    inputs.file(bundledBackend)
    doLast {
        if (!bundledBackend.asFile.isFile) {
            throw GradleException(
                "Bundled backend is missing. Run .\\tools\\build_backend.ps1 from the repository root before packaging."
            )
        }
    }
}

val verifyPreparedBackend = tasks.register("verifyPreparedBackend") {
    dependsOn("prepareAppResources")
    val preparedBackend = layout.buildDirectory.file(
        "compose/tmp/prepareAppResources/backend/gpu-monitor-backend.exe",
    )
    doLast {
        if (!preparedBackend.get().asFile.isFile) {
            throw GradleException(
                "Bundled backend was not copied into the Compose application resources. " +
                    "Put it under appResources/common/backend or a platform-specific appResources directory."
            )
        }
    }
}

tasks.matching {
    it.name in setOf("packageExe", "packageMsi", "packageDistributionForCurrentOS")
}.configureEach {
    dependsOn(verifyBundledBackend, verifyPreparedBackend)
}
