import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Desktop launcher: a JVM Compose application that wires the framework together. It initializes
// platform-core, opens a project through project-model-impl, drives analysis/completion through
// lang-jdt (all via the UI-agnostic IdeServices facade), and renders the reusable Compose UI from
// :ide-ui — implementing :ide-ui's IdeBackend port over IdeServices. The Swing layout is gone.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    // The shared backend + UI port (ide-core api-exposes :ide-ui). The desktop launcher adds only its
    // Compose-window main() on top. The framework impls come transitively from :ide-core.
    implementation(project(":ide-core"))

    // The Compose @Preview render surface (the interpreter's render half): on desktop it drives Compose for
    // Desktop, so a @Preview using standard material/foundation composables renders live (the same bridge
    // :ide-android uses on device). Re-exports :interp-core's ResolvedFunction, which the host consumes.
    implementation(project(":interp-compose"))

    // Project-resource resolution for the preview (`stringResource(R.string.x)`/`colorResource`/…) — the merged
    // resource repository + aapt-shaped R ids (:android-support) and the value engine (:layout-preview-impl),
    // wired into a DesktopPreviewResources. Both are pure-JVM and already transitive runtime deps via :ide-core;
    // added directly for compile visibility.
    implementation(project(":android-support"))
    implementation(project(":layout-preview-impl"))

    implementation(compose.desktop.currentOs)
    // material3 for the preview host's progress/empty-state chrome (compose.desktop.currentOs bundles M2;
    // :ide-ui keeps material3 as a non-transitive `implementation`).
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for the Compose/AWT thread
    implementation(libs.jbr.api) // custom window title bar when running on the JetBrains Runtime

    // bundletool builds the .aab in-process (android-support keeps it compileOnly). The desktop launcher
    // bundles it so the `bundle<Variant>` task works; pure Java, so it just runs on the JVM.
    implementation(libs.android.bundletool)
    // Bouncy Castle: in-process keystore creation (android-support keeps it compileOnly) — same code path on
    // desktop and device, so the desktop launcher bundles it too.
    implementation(libs.bouncycastle.pkix)
    // D8 and apksigner, run in-process when the machine has no Android SDK (android-support keeps them
    // compileOnly; the device app bundles them the same way). See BundledAndroidToolchain.
    implementation(libs.android.r8)
    implementation(libs.android.apksig)
}

// --- The bundled Android toolchain ----------------------------------------------------------------------------
// So an Android project builds on a machine with no Android SDK, the app ships what the build would otherwise take
// from one: the platform android.jar and the desugar stubs (the same files :ide-android bundles) and a host aapt2,
// which Google publishes per OS on Maven (the one AGP itself runs). It lands in the app resources, which the
// launcher hands to BundledAndroidToolchain. jpackage cannot cross-build, so the aapt2 is the build host's.
val hostAapt2Classifier: String = System.getProperty("os.name").lowercase().let {
    when {
        "mac" in it -> "osx"
        "win" in it -> "windows"
        else -> "linux"
    }
}
val aapt2Tool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    aapt2Tool(variantOf(libs.android.aapt2) { classifier(hostAapt2Classifier) }) { isTransitive = false }
}

val appResourcesRoot = layout.buildDirectory.dir("app-resources")
val stageAndroidToolchain = tasks.register<Sync>("stageAndroidToolchain") {
    description = "Stage android.jar, the desugar stubs and the host aapt2 into the desktop app resources."
    into(appResourcesRoot.map { it.dir("common/android-toolchain") })
    from(rootProject.file("app/ide-android/src/main/sdk/android.jar"))
    from(rootProject.file("app/ide-android/src/main/assets/core-lambda-stubs.jar"))
    from(aapt2Tool.elements.map { files -> files.map { zipTree(it.asFile) } }) {
        include("aapt2", "aapt2.exe", "*.dll")
        filePermissions { unix("rwxr-xr-x") }
    }
}

// The installer carries the IDE's release version. The Android app's versionName is the one version file, so
// read it from there; a release build can still pass `-PdesktopVersion=X.Y.Z`.
val desktopVersion: String = providers.gradleProperty("desktopVersion").orNull
    ?: Regex("""versionName\s*=\s*"([^"]+)"""")
        .find(rootProject.file("app/ide-android/build.gradle.kts").readText())
        ?.groupValues?.get(1)
    ?: error("no versionName in app/ide-android/build.gradle.kts; pass -PdesktopVersion")

compose.desktop {
    application {
        mainClass = "dev.ide.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CodeAssist"
            packageVersion = desktopVersion
            description = "Android and Kotlin IDE"
            vendor = "CodeAssist"
            // The bundled runtime is also the JDK user projects compile and complete against, and kotlinc needs
            // java.management: a runtime trimmed to the modules the IDE itself links fails every Kotlin build.
            includeAllModules = true
            appResourcesRootDir.set(appResourcesRoot)
            macOS {
                bundleID = "com.tyron.code.desktop"
                iconFile.set(project.file("icons/CodeAssist.icns"))
            }
            windows {
                iconFile.set(project.file("icons/CodeAssist.ico"))
                menuGroup = "CodeAssist"
                shortcut = true
                // Installs into the user's AppData: no administrator prompt, and nothing in the install dir is
                // written to at runtime anyway.
                perUserInstall = true
                dirChooser = true
                // Keeps upgrades replacing the installed version instead of installing beside it. Never change it.
                upgradeUuid = "6f0d3c1e-8a4b-4e2f-9c7d-2b5a1e8f4c90"
            }
            linux {
                iconFile.set(project.file("icons/CodeAssist.png"))
                packageName = "codeassist"
                shortcut = true
            }
        }
    }
}

// jlink strips the runtime's bin/ by default, but a desktop Android build over an installed SDK forks `java` (d8,
// apksigner) and every desktop build creates its debug keystore with `keytool`, both from java.home/bin: without
// them the SDK reads as incomplete and assemble fails "Android SDK not found". The plugin keeps this switch
// internal, hence the reflection.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    @Suppress("UNCHECKED_CAST")
    (javaClass.getMethod("getStripNativeCommands\$compose").invoke(this) as Property<Boolean>).set(false)
}

// Every task that reads the app resources (the packagers' prepareAppResources, and `run`) needs the toolchain staged.
tasks.matching { it.name == "prepareAppResources" || it.name == "run" }.configureEach { dependsOn(stageAndroidToolchain) }
