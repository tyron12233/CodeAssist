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
            // `api` so the UI modules split out of here still see the one shared `Res` class; the
            // resources themselves live in :ide-ui-resources (see its build script for why).
            api(project(":ide-ui-resources"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.preview) // the @Preview annotation (BlockEditor.kt previews)
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
        // StringResourceEscapingTest scans the raw strings.xml files, which live in :ide-ui-resources; the
        // directory is passed in below rather than spelled out in Kotlin, so moving that module cannot
        // silently turn the scan into a no-op.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test) // virtual-clock tests for the editor engine daemon
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
