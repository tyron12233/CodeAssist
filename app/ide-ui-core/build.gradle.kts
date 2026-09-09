import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The layer the rest of the IDE UI is built on: the theme, the platform expect/actual surface, the editor's
// document/session model, and the application state every screen reads.
//
// It exists because :ide-ui was one 63k-line module whose dependency graph was not a graph. `AppState`
// reached up into the editor for `EditorSession` and `languageFor`; the editor's own state layer reached up
// into the editor's composables for `shiftDiagnostics`; the preview's model types were declared inside the
// composables that render them. Everything the upper layers *share* is collected here, and only things with
// no dependency on a screen, a component or a composable editor are allowed in.
//
// The packages are unchanged from when this code lived in :ide-ui -- `dev.ide.ui`, `dev.ide.ui.theme`,
// `dev.ide.ui.editor.core` and the rest -- so the split moved no imports at all. What moved is the module
// boundary, and with it the guarantee that the arrows only point one way.
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
        namespace = "dev.ide.ui.core"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` for both: the types in this module's own signatures come from them (IdeBackend and the
            // DTOs from :ide-ui-api, the `Res` accessors the theme reads from :ide-ui-resources), so every
            // consumer needs them on its compile classpath too.
            api(project(":ide-ui-api"))
            api(project(":ide-ui-resources"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            // `api`: @Preview-annotated composables exist in every UI layer (BlockShapes here, the tab
            // strip in components, the block surface in the editor), so expose it once from the bottom.
            api(compose.preview)
            implementation(libs.kotlinx.coroutines.core)
        }

        // RopeTest drives the editor's text rope through its internal structure (node depth, rebalancing),
        // which only a test inside this module can see. A test that needs nothing internal stays in the
        // module whose behaviour it describes.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":ide-ui-testing"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.desktop.currentOs) // skiko, for the text-measurement and rope benchmarks
            }
        }

        androidMain.dependencies {
            // androidx.activity.compose.BackHandler — backs PlatformBackHandler's Android actual.
            implementation(libs.androidx.activity.compose)
        }
    }
}
