import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Compose half of the interpreter (see docs/compose-interpreter.md, step 4): the reflective Compose-ABI
// bridge + the `@Composable` render surface that drive the **real** Compose runtime from the interpreter.
// Authored once in Compose Multiplatform so the SAME bridge runs on both hosts — :ide-android (Compose for
// Android, on device) and :ide-desktop (Compose for Desktop, JVM) — letting the editor's @Preview render
// live pixels on either. It is host-agnostic: it only touches `androidx.compose.runtime` (whose package
// names are identical across platforms) plus the plain-JVM :interp-core, so a preview that uses standard
// material/foundation composables resolves against whichever Compose the host bundles.
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

    // compileSdk 36 / minSdk 24 — match :ide-ui (the AndroidX/Compose runtime floor). No compileSdkMinor
    // here; only the app module pins the minor.
    android {
        namespace = "dev.ide.interp.compose"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
        }

        // An intermediate JVM-only source set shared by the desktop + android targets, so it can depend on
        // the plain-JVM :interp-core (which commonMain — platform-agnostic — cannot). All of the bridge +
        // renderer lives here; both targets see it. Mirrors :ide-ui's `jvmShared`.
        val jvmShared = create("jvmShared") {
            dependsOn(getByName("commonMain"))
            // `api`: :interp-core re-exports :lang-kotlin's ResolvedFunction, which appears in the renderer's
            // public signature, so consumers (the hosts) need it on their compile classpath.
            dependencies {
                api(project(":interp-core"))
                // The bytecode VM behind VmLibraryExecutor (project-jar dependency code runs interpreted in
                // the preview instead of through a DexClassLoader). `api`: PeerFactory is in its constructor
                // signature (the Android host supplies a dex-generating factory).
                api(project(":jvm-interp"))
                // compileOnly: the renderer provides `LocalInspectionMode = true` around the preview (so
                // Popup/Dialog/DropdownMenu render inline and tooling-aware components behave, exactly as a real
                // @Preview does). LocalInspectionMode lives in compose.ui; keep it OFF the runtime artifact so it
                // still resolves against whichever Compose the host bundles (the module's host-agnostic contract).
                compileOnly(compose.ui)
            }
        }
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)

        // Desktop unit tests: drive the reflective Compose-ABI bridge against REAL Compose-compiled composables
        // (the Compose plugin transforms test sources too) inside a headless composition — verifies default-
        // parameter $default bitmasks without a device.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.runtime)
                implementation(compose.material3) // verify mangled-name composable detection against real Material3
                // The Skiko native runtime (per-OS). Constructing a real Compose `LayoutNode` runs
                // `InnerNodeCoordinator`'s static `Paint()` → `SkiaBackedPaint`, which loads the Skiko native lib
                // — so the milestone-A phase-B node-tree spikes (which emit real `LayoutNode`s through the
                // interpreted ui/foundation stack) need the graphics floor present even headless. No display is
                // opened; only the native lib is loaded. currentOs resolves the matching skiko-awt-runtime.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// Every preview fixture lowers against the whole Compose test classpath, and a `KotlinSymbolService` scans
// each Kotlin jar on its classpath for extensions/top-level callables. The scan result is content-keyed per
// jar, so the fixtures share one cache directory (see `InterpTestFixtures.kt`) and the scan becomes a
// once-per-classpath cost instead of a per-test one. It lives outside the test task's own output dir, so
// `cleanDesktopTest` (which forces a re-run) doesn't throw it away.
tasks.withType<Test>().configureEach {
    systemProperty(
        "interp.test.scanCache",
        layout.buildDirectory.dir("tmp/classpath-scan-cache").get().asFile.absolutePath,
    )
}
