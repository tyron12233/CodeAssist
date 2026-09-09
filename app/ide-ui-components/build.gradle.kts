import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The reusable widgets the IDE is assembled from: buttons, sheets, dialogs, the file tree, the build
// console, the ad slot, and the one shared Markdown renderer. A component knows the theme, the backend port
// and the app state; it never knows which screen is showing it, which is what makes it a component.
//
// `markdown/` and `ads/` sit here rather than with the screens because they are leaves that import nothing
// above them, and both the editor and the screens read them.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "dev.ide.ui.components"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: these types appear in the components' own signatures, so screens and the editor need
            // them too. :ide-ui-core re-exposes Compose and :ide-ui-api the same way.
            api(project(":ide-ui-core"))
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":ide-ui-testing"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.desktop.currentOs) // skiko, for the off-screen snapshot assertions
            }
        }
    }
}
