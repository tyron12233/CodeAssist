import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app shell: the navigation graph, the overlay stack, the root composable, and the application state
// object that wires them to a host. It is what a launcher calls -- :ide-desktop and :ide-android each build
// an `IdeBackend` and hand it to `CodeAssistApp` -- and it is the only UI module that knows what all the
// screens are.
//
// Everything it renders lives below it, one module per layer: :ide-ui-core (theme, platform, editor model,
// app state), :ide-ui-components, :ide-ui-editor, :ide-ui-screens. The split is not cosmetic -- before it,
// this module was 63k lines whose graph ran in both directions between the shell and the screens.
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

    // compileSdk 36 = the installed `android-36.1` platform (Android 16); minSdk 24 is the floor for the
    // AndroidX/Compose runtime the hosts pull in. (The KMP `android {}` block has no compileSdkMinor; the
    // app module — which does — pins the minor.)
    android {
        namespace = "dev.ide.ui"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` throughout: :ide-core renders `CodeAssistApp` and builds the state it takes, so the
            // types from every layer below reach it through this module.
            api(project(":ide-ui-api"))
            api(project(":ide-ui-resources"))
            api(project(":ide-ui-core"))
            api(project(":ide-ui-components"))
            api(project(":ide-ui-editor"))
            api(project(":ide-ui-screens"))
        }

        // Tests for the shell itself: the app state object, the save action, and the plugin-surface seam.
        // StringResourceEscapingTest scans the raw strings.xml files, which live in :ide-ui-resources; the
        // directory is passed in below rather than spelled out in Kotlin, so moving that module cannot
        // silently turn the scan into a no-op.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":ide-ui-testing"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.desktop.currentOs) // skiko native runtime for off-screen ImageComposeScene snapshots
            }
        }
    }
}

// StringResourceEscapingTest reads the strings.xml sources directly (see its KDoc: it covers every string in
// every locale without naming them). They belong to :ide-ui-resources, and only the build knows where that
// module sits, so hand the test the path instead of letting it guess a relative one.
tasks.named<Test>("desktopTest") {
    systemProperty(
        "ide.ui.composeResources",
        project(":ide-ui-resources").layout.projectDirectory.dir("src/commonMain/composeResources").asFile.absolutePath,
    )
}
