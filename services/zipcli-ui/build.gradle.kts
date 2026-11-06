import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The ZipCLI terminal, as a self-contained UI plugin module: a bottom tool window (one tab beside the build
// console's Log / Problems / App log) that runs ZipCLI commands over the open project. It reuses the :ide-ui
// shell's theme and talks to the IDE only through the :ide-ui-api IdeBackend port. The terminal state and the
// panel are plain commonMain Compose; the engine (:zipcli-core) is JVM-only, so the one file that touches it
// lives in `jvmShared`, the same desktop + Android split :ide-ui-api uses for its JVM-only bridge.
// ide-core references ZipCliUiPlugin from here for the unified BuiltInPlugins declaration.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "dev.ide.zipcli.ui"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `:ide-ui` re-exposes `:ide-ui-api` (IdeBackend + the UiPlugin / ToolWindow SPI) via `api`.
            implementation(project(":ide-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }

        // Desktop and Android are both plain JVM, so the engine glue is written once and shared by the two.
        val jvmShared = create("jvmShared") {
            dependsOn(getByName("commonMain"))
            dependencies { implementation(project(":zipcli-core")) }
        }
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)
    }
}
