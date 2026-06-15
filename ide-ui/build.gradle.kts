import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The reusable IDE UI, authored once in Compose Multiplatform `commonMain` so the same composables run
// on desktop and Android. It is toolkit- and backend-agnostic: it talks only to the `IdeBackend` port
// (see commonMain/.../backend), which each host implements — ide-desktop over IdeServices, ide-android
// over its own backend. Both the desktop (JVM) and Android targets are wired here; commonMain is shared
// verbatim. Under AGP 9 the Android target is declared via the `com.android.kotlin.multiplatform.library`
// plugin inside the `kotlin {}` block (the old `androidTarget()` + top-level `android {}` form is gone).
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
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.preview) // the @Preview annotation (BlockEditor.kt previews)
            implementation(libs.kotlinx.coroutines.core)
        }

        // `compose.uiTooling` carries `ComposeViewAdapter`, the harness the preview pane instantiates to
        // render an @Preview. Without it on the target's classpath, Studio/IntelliJ fail with
        // `ClassNotFoundException: androidx.compose.ui.tooling.ComposeViewAdapter`. Added per target so
        // both the Android (Studio) and desktop (IntelliJ) preview renderers can find it.
        androidMain.dependencies {
            implementation(compose.uiTooling)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.uiTooling)
            }
        }

        // Unit tests for the toolkit-agnostic editor logic (state, diagnostic shifting) on the JVM target.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}