import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish-mpp")
}

// plugin-api — the SPI for UI-contributed plugin features.
//
// The lean action model (IdeAction / ActionGroup + named places) and the data-driven UI extension points,
// so a toolbar button, a menu item, or a command-palette command is a registration against an extension
// point rather than an edit to the host UI. Pure Kotlin: no Compose, no engine, no project model. The
// engine side (ide-core) registers built-ins here; the Compose UI renders them through neutral DTOs over
// the IdeBackend port, exactly like the settings framework.
//
// Multiplatform: a plugin's UI half can be built for iOS, so what it registers against has to be too.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    // HostPath: `java.nio.file.Path` on the JVM by a typealias, so the SPI's JVM signatures do not change.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain.dependencies {
            // ExtensionPoint/ExtensionRegistry, PluginId, Disposable — the EPs and the ids are part of the SPI.
            api(project(":platform-core"))
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":test-support"))
            implementation(libs.kotlin.test)
        }

        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
    }
}

dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
