import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "local.gpu.monitor"
version = "0.2.1"

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
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "gpu-monitor-kyant"
            packageVersion = "0.2.1"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            windows {
                shortcut = true
                menuGroup = "GPU Monitor"
                perUserInstall = true
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
            }
        }
    }
}
