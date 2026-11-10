import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android layout editor, built the way an INSTALLED plugin is built even though it ships inside the IDE:
// one module holding both facets (the engine facet, `LayoutEditorPlugin`, and the UI facet,
// `LayoutEditorUiPlugin`), compiled ONLY against the plugin SPI. It names no `:ide-*` module: everything it
// knows about the project comes through the SPI's services (the layout renderer, the layout model), and
// everything it shows goes through `plugin-ui-api` contributions. Where that SPI fell short, the SPI grew;
// that is the point of building it this way. `:ide-core` pairs the two facets in BuiltInPlugins.
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
        namespace = "dev.ide.android.layouteditor"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // The SPI, and nothing else of the IDE's.
            implementation(project(":plugin-api"))
            implementation(project(":plugin-ui-api"))
            implementation(project(":android-layout-api"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources) // the strings + the Material Symbols subset it ships
            implementation(libs.kotlinx.coroutines.core)
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "dev.ide.android.layouteditor.generated.resources"
    generateResClass = always
}
