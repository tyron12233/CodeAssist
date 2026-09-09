import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The code editor: the canvas and its gutter, completion and the popups around it, find/replace, the block
// (projectional) surface, folding chrome, and the preview panes. The editor's document and session model is
// NOT here -- it is in :ide-ui-core, because the application state reads it too. What is here is everything
// that draws.
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
        namespace = "dev.ide.ui.editor"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ide-ui-core"))
            api(project(":ide-ui-components")) // IconButtonCa, KindBadge, CaDropdownMenu, the entrance/press motion
        }

        // A JVM-only intermediate source set shared by the desktop + android targets, so it can depend on the
        // plain-JVM `layout-preview-api` (which commonMain, being platform-agnostic, cannot). The XML layout
        // preview pane and its Compose-backed RCanvas live here; commonMain reaches them via expect/actual.
        val jvmShared = create("jvmShared") {
            dependsOn(getByName("commonMain"))
            dependencies { implementation(project(":layout-preview-api")) }
        }
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)

        // `compose.uiTooling` carries `ComposeViewAdapter`, the harness the preview pane instantiates to
        // render an @Preview. Without it on the target's classpath, Studio/IntelliJ fail with
        // `ClassNotFoundException: androidx.compose.ui.tooling.ComposeViewAdapter`.
        androidMain.dependencies { implementation(compose.uiTooling) }
        val desktopMain by getting { dependencies { implementation(compose.uiTooling) } }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":ide-ui-testing"))
                implementation(libs.kotlinx.coroutines.test) // virtual-clock tests for the editor engine daemon
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
