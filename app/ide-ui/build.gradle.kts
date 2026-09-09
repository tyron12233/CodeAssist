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
            // The IdeBackend port + DTOs + UI-contribution model live in :ide-ui-api; `api` re-exposes them so
            // :ide-core (which depends on :ide-ui) keeps seeing them transitively without a build change.
            api(project(":ide-ui-api"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.preview) // the @Preview annotation (BlockEditor.kt previews)
            implementation(compose.components.resources) // bundled fonts (JetBrains Mono) via composeResources/
            implementation(libs.kotlinx.coroutines.core)
        }

        // An intermediate JVM-only source set shared by the desktop + android targets, so it can depend on
        // the plain-JVM `layout-preview-api` (which commonMain — platform-agnostic — cannot). The layout
        // preview pane + its Compose-backed RCanvas live here; commonMain reaches them via an expect/actual.
        val jvmShared = create("jvmShared") {
            dependsOn(getByName("commonMain"))
            dependencies { implementation(project(":layout-preview-api")) }
        }
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)

        // `compose.uiTooling` carries `ComposeViewAdapter`, the harness the preview pane instantiates to
        // render an @Preview. Without it on the target's classpath, Studio/IntelliJ fail with
        // `ClassNotFoundException: androidx.compose.ui.tooling.ComposeViewAdapter`. Added per target so
        // both the Android (Studio) and desktop (IntelliJ) preview renderers can find it.
        androidMain.dependencies {
            implementation(compose.uiTooling)
            // androidx.activity.compose.BackHandler — backs the platform back handler (PlatformBackHandler).
            implementation(libs.androidx.activity.compose)
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
                implementation(libs.kotlinx.coroutines.test) // virtual-clock tests for the editor engine daemon
                implementation(compose.desktop.currentOs) // skiko native runtime for off-screen ImageComposeScene snapshots
            }
        }
    }
}

// Generated accessor for the bundled fonts (JetBrains Mono lives under commonMain/composeResources/font/).
// Pinned package + non-public so it stays an internal `dev.ide.ui` detail the theme reads.
compose.resources {
    publicResClass = false
    packageOfResClass = "dev.ide.ui.generated.resources"
    generateResClass = always
}