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
    // The Supabase-backed Projects Store: catalog, sign-in, publishing, reviews, moderation, daily challenges.
    // ide-core keeps store-impl internal; the launcher is what constructs it (as :ide-android does).
    implementation(project(":store-impl"))

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

// The Supabase endpoint and the judge, the same values and override chain (-PSUPABASE_URL or the SUPABASE_URL env
// var, likewise for the key and JUDGE_URL) :ide-android bakes into BuildConfig and :ide-ios generates, so a rotated
// key reaches every host the same way. There is no BuildConfig in a JVM module, so it is generated.
//
// The publishable key is safe to ship in an open-source client ONLY because row-level security is what actually
// gates access. An empty URL leaves the store wired but inert (the bundled catalog, no sign-in), which is what a
// fork with no endpoint of its own should get.
val supabaseUrl = (findProperty("SUPABASE_URL") as String?) ?: System.getenv("SUPABASE_URL")
    ?: "https://lqlpkeummmmglikumotx.supabase.co"
val supabaseKey = (findProperty("SUPABASE_KEY") as String?) ?: System.getenv("SUPABASE_KEY")
    ?: "sb_publishable_5T14bUAG6fOGz47kwYzG7A_25dj3ap4"
val judgeUrl = (findProperty("JUDGE_URL") as String?) ?: System.getenv("JUDGE_URL")
    ?: "https://codeassist-judge.vercel.app"
// The R2 mirror of the public store files; blank reads Supabase Storage (see supabase/functions/r2-mirror).
val storeMediaUrl = (findProperty("STORE_MEDIA_URL") as String?) ?: System.getenv("STORE_MEDIA_URL")
    ?: "https://pub-a5be92b9c0b14a86ae36e09cb86f0ffa.r2.dev"
// The catalog hides items that need a newer IDE than this one, compared against the Android versionCode: the
// desktop build ships from the same tree, so it is the same IDE.
val storeAppBuild: Int = Regex("""versionCode\s*=\s*(\d+)""")
    .find(rootProject.file("app/ide-android/build.gradle.kts").readText())
    ?.groupValues?.get(1)?.toInt()
    ?: error("no versionCode in app/ide-android/build.gradle.kts")

val generateStoreConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/storeConfig/kotlin")
    // Declared as inputs so a changed endpoint regenerates rather than being served from the build cache.
    inputs.property("url", supabaseUrl)
    inputs.property("key", supabaseKey)
    inputs.property("judge", judgeUrl)
    inputs.property("media", storeMediaUrl)
    inputs.property("build", storeAppBuild)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/ide/desktop/store/StoreConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package dev.ide.desktop.store

            /** Generated from Gradle properties; see app/ide-desktop/build.gradle.kts. Do not edit. */
            internal object StoreConfig {
                const val SUPABASE_URL: String = "$supabaseUrl"
                const val SUPABASE_KEY: String = "$supabaseKey"
                const val JUDGE_URL: String = "$judgeUrl"
                const val STORE_MEDIA_URL: String = "$storeMediaUrl"
                const val APP_BUILD: Int = $storeAppBuild
            }

            """.trimIndent(),
        )
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateStoreConfig) }

// The installer carries the IDE's release version. The Android app's versionName is the one version file, so
// read it from there; a release build can still pass `-PdesktopVersion=X.Y.Z`.
val desktopVersion: String = providers.gradleProperty("desktopVersion").orNull
    ?: Regex("""versionName\s*=\s*"([^"]+)"""")
        .find(rootProject.file("app/ide-android/build.gradle.kts").readText())
        ?.groupValues?.get(1)
    ?: error("no versionName in app/ide-android/build.gradle.kts; pass -PdesktopVersion")

// Every module of the JDK that packages the app (the Gradle daemon's JBR, which the plugin links from), less the
// JetBrains Runtime's embedded Chromium and its OpenGL bindings: nothing here uses them, and on Windows JCEF alone
// is ~270 MB (libcef.dll, dxcompiler.dll, resources.pak). Read from jmods/ since the set differs per OS.
val bundledRuntimeModules: List<String> =
    (File(System.getProperty("java.home"), "jmods").listFiles() ?: emptyArray())
        .map { it.name.removeSuffix(".jmod") }
        .filter { it !in setOf("jcef", "jogl.all", "gluegen.rt") }
        .sorted()
        .ifEmpty { error("no jmods in ${System.getProperty("java.home")}: package with a JDK that has them (the JBR SDK)") }

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
            modules(*bundledRuntimeModules.toTypedArray())
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
