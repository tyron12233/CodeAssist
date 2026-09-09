import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens: the project home, the store, Learn and the daily challenges, settings, the run and logs
// views, the SDK and icon managers. A screen composes components and the editor into one destination; the
// shell (:ide-ui) is what routes between them.
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
        namespace = "dev.ide.ui.screens"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ide-ui-core"))
            api(project(":ide-ui-components"))
            api(project(":ide-ui-editor"))
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":ide-ui-testing"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
