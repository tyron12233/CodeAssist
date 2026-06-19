package dev.ide.android

import android.content.Context
import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.core.ProjectManager
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipInputStream
import java.nio.file.Paths

/**
 * On-device bootstrap for the IDE engine, the Android counterpart to :ide-desktop's wiring. ART has no
 * JDK to detect, so the Android SDK's `android.jar` (signatures for `java.*` + `android.*`) ships as an
 * asset, is copied into app storage once, and is fed as each workspace's boot classpath. The whole app
 * directory lives under `<external-files>/codeassist` (projects in `projects/`, one workspace dir each) —
 * app-specific external storage, so a [ProjectsDocumentsProvider] can surface that directory in the system
 * Files app / any file manager without the All-Files-Access permission. A [ProjectManager] creates/opens/lists
 * the projects, so the IDE supports live
 * in-session switching. On first launch the Android multi-module sample is seeded. Everything above that
 * (project model, JDT completion/analysis, indexing, the Android build) is the same `IdeServices` the
 * desktop runs, surfaced through the same `IdeServicesBackend`.
 */
object AndroidIde {

    /** Heavy (file copy + project gen + JDT init) — call off the main thread. */
    fun bootstrap(context: Context): Session {
        val startNs = System.nanoTime()
        // App-specific EXTERNAL storage (`Android/data/<pkg>/files/codeassist`): no permission needed, yet
        // reachable by other file managers via [ProjectsDocumentsProvider]. Fall back to internal storage
        // only if external isn't mounted (rare). The location is resolved identically by the provider.
        val home = appHomeDir(context).apply { mkdirs() }
        val androidJar = copyAsset(context, "android.jar", File(home, "android.jar"))
        // The on-device Kotlin compiler (K2JVMCompiler) is dexed, but IntelliJ-core boots its extension
        // registry by reading XML descriptors (META-INF/extensions/*.xml) from a real filesystem path, which
        // a dex APK doesn't expose. Extract the bundled kotlinc-resources.zip (the compiler jar minus its
        // .class entries) to a home dir and publish it via `kotlinc.art.home` — the value the ASM-patched
        // PathUtil reads (see build-logic dev.ide.build.kotlinc.PathUtilSelfLocatePass). Without this, the
        // first Kotlin compile throws "Unable to find extension point configuration .../compiler-cli-root.xml".
        provisionKotlincHome(context, File(home, "kotlinc-home"))
        // The debug keystore is a shared, non-secret credential; ART has no `keytool`, so a prebuilt
        // PKCS12 keystore ships as an asset and is copied out for apksig (in-process) to sign with.
        val debugKeystore = copyAsset(context, "debug.keystore", File(home, "debug.keystore"))
        // The aapt2/zipalign prebuilts are packaged as lib*.so and extracted here at install time — the only
        // directory ART permits executing binaries from.
        val nativeLibDir = Paths.get(context.applicationInfo.nativeLibraryDir)

        val projectsRoot = File(home, "projects").toPath()
        val bootClasspath = listOf(androidJar.absolutePath)
        // Runs a dexed Java console app in-process (ART has no `java` to fork); the oat cache is transient.
        val dexRunner = DexClassLoaderRunner(File(context.cacheDir, "dexrun"))
        // Installs + launches a built APK (the android Run) via the system package installer.
        val apkInstaller = ApkInstallerImpl(context)
        // Renders live custom views in the layout preview: D8-dex the instrumented classes + DexClassLoader.
        val previewRuntime = dev.ide.preview.bridge.DexCustomViewRuntime(
            context.applicationContext, androidJar.toPath(),
            File(context.cacheDir, "preview"), android.os.Build.VERSION.SDK_INT,
        )
        // Project data left by previous app versions (same `com.tyron.code` package, so the same external
        // files dir survives a Play update). Swept into backups, and recovered into the picker by
        // `importLegacyProjects` when in a loadable format. Two known locations:
        //  - `<external-files>/Projects` — the v0.2.9 (legacy, Gradle) projects dir (`getExternalFilesDir("Projects")`).
        //  - `filesDir/codeassist` — an early internal-storage home of THIS app, before the move to external.
        // The v0.2.9 projects aren't openable here (no Gradle sync yet) but their sources are recoverable via
        // the backup and the file manager (this dir is a sibling of our home, both under [externalHome]).
        val legacyProjectsDir = File(externalHome(context), "Projects").toPath()
        val legacyInternalHome = File(context.filesDir, "codeassist").toPath()
        val legacyDataDirs = listOf(legacyProjectsDir, legacyInternalHome)
            .filter { java.nio.file.Files.exists(it) }
        val manager = ProjectManager.onDevice(
            projectsRoot, bootClasspath, nativeLibDir, debugKeystore.toPath(),
            storageRoot = externalHome(context).toPath(),
            legacyDataDirs = legacyDataDirs,
            dexRunner = dexRunner,
            deviceApiLevel = android.os.Build.VERSION.SDK_INT,
            apkInstaller = apkInstaller,
            customViewRuntime = previewRuntime,
        )

        // Recover projects left in internal storage by a build from before the move to external app storage
        // (issues #1003 / #1024 / #1041 / #1042: projects vanishing from the picker after an update). Runs at
        // most once; non-destructive. Done before the empty-check so a recovered project opens instead of the
        // first-run demo being seeded over it.
        runCatching { manager.importLegacyProjects() }

        val services = if (manager.isEmpty()) {
            // Seed the rich Android multi-module sample (app → feature → core) into its own workspace dir.
            // Java 8 level: a bundled non-modular android.jar keeps JDT DOM analysis working on ART.
            IdeServices.bootstrapWithBootClasspath(
                root = projectsRoot.resolve("android-sample"),
                bootClasspath = bootClasspath,
                sdkName = "android-36",
                generateDemo = true,
                androidToolsDir = nativeLibDir,
                debugKeystore = debugKeystore.toPath(),
                dexRunner = dexRunner,
                deviceApiLevel = android.os.Build.VERSION.SDK_INT,
                apkInstaller = apkInstaller,
                customViewRuntime = previewRuntime,
                // Share the download cache with every later project (the app home, sibling of projects/).
                sharedCachesRoot = home.toPath(),
            )
        } else {
            manager.open(manager.list().first().rootPath)
        }

        // Opt-in usage analytics (no collection until the user grants consent — see docs/analytics.md).
        val analytics = buildAnalytics(manager, home)
        val backend = IdeServicesBackend(services, manager, analytics)
        // Process-wide uncaught-exception handler: report app_crash + surface the non-fatal dialog + keep the
        // app alive (the MainActivity main-thread guard handles the UI looper). See IdeServicesBackend.
        backend.installCrashReporting()
        // cold_start: time the whole on-device bootstrap (asset copy + project load + engine init). Emitted
        // once per launch for users who consented; no-op otherwise. Also serves as the per-launch anchor.
        if (backend.analyticsConsent() == true) {
            backend.track(dev.ide.analytics.Events.COLD_START, mapOf("duration_ms" to ((System.nanoTime() - startNs) / 1_000_000).toString()))
        }

        return Session(services, backend)
    }

    /**
     * Build the analytics service from the baked-in Supabase config. Returns the no-op service when no
     * endpoint is configured (a fork building without a key) so the rest of the app is unaffected. The
     * install id is a random UUID persisted once in prefs (not tied to any account); the session id is fresh
     * per launch. The service starts gated on the stored consent and collects nothing until it's granted.
     */
    private fun buildAnalytics(manager: ProjectManager, home: File): dev.ide.analytics.AnalyticsService {
        val url = BuildConfig.ANALYTICS_URL
        val key = BuildConfig.ANALYTICS_KEY
        if (url.isBlank() || key.isBlank()) return dev.ide.analytics.NoopAnalyticsService

        val installId = manager.preference("analytics.install.id")
            ?: java.util.UUID.randomUUID().toString().also { manager.setPreference("analytics.install.id", it) }
        val device = dev.ide.analytics.DeviceInfo(
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = BuildConfig.VERSION_CODE,
            osApi = android.os.Build.VERSION.SDK_INT,
            deviceModel = android.os.Build.MODEL ?: "",
            deviceManufacturer = android.os.Build.MANUFACTURER ?: "",
            abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "",
            locale = java.util.Locale.getDefault().toLanguageTag(),
        )
        val service = dev.ide.analytics.impl.DefaultAnalyticsService(
            installId = installId,
            sessionId = java.util.UUID.randomUUID().toString(),
            device = device,
            sink = dev.ide.analytics.impl.SupabaseSink(url, key),
            initialConsent = manager.preference("analytics.consent") == "granted",
            queueFile = File(home, "analytics-queue.txt").toPath(),
        )
        // Bridge the logging facade to analytics: caught ERROR logs become scrubbed `error_logged` events
        // (no messages/paths). No-ops while the service is disabled, and starts working on consent.
        dev.ide.platform.log.Log.addSink(dev.ide.analytics.impl.AnalyticsLogSink(service))
        return service
    }

    /** App-specific external storage base (`Android/data/<pkg>/files`), or internal `filesDir` if external
     *  isn't currently mounted. Resolved the same way by [bootstrap] and [ProjectsDocumentsProvider] so both
     *  see one projects directory. */
    fun externalHome(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** The whole CodeAssist app directory (`<external-files>/codeassist`): projects, the SDK `android.jar`,
     *  the debug keystore, the kotlinc home, shared caches. This is the root surfaced to file managers. */
    fun appHomeDir(context: Context): File = File(externalHome(context), "codeassist")

    /** The on-disk projects directory (`<external-files>/codeassist/projects`). */
    fun projectsDir(context: Context): File = File(appHomeDir(context), "projects")

    /** Copy a bundled asset into app storage once (assets are read-only in the APK). */
    private fun copyAsset(context: Context, name: String, dest: File): File {
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    /**
     * Extract the bundled kotlinc-resources.zip asset (the compiler's non-class resources) into [home] and
     * set the `kotlinc.art.home` system property to that path. Idempotent and re-extracts only when the app
     * has been updated since the last extraction (a new APK may carry a new compiler), tracked by a marker
     * holding the package's `lastUpdateTime`.
     */
    private fun provisionKotlincHome(context: Context, home: File) {
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        val marker = File(home, ".provisioned")
        if (marker.exists() && marker.readText() == stamp) {
            System.setProperty("kotlinc.art.home", home.absolutePath)
            return
        }

        home.deleteRecursively()
        home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        context.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    // Zip-slip guard (a controlled archive, but cheap to be safe).
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        marker.writeText(stamp)
        System.setProperty("kotlinc.art.home", home.absolutePath)
    }

    /** The live engine + its UI-port adapter; [backend] is held so the Activity can close it on teardown. */
    class Session(val services: IdeServices, val backend: IdeServicesBackend)
}
