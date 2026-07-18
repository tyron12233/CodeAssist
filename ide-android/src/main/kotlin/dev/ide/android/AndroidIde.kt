package dev.ide.android

import android.content.Context
import android.os.Build
import dev.ide.analytics.DeviceInfo
import dev.ide.analytics.impl.AnalyticsLogSink
import dev.ide.analytics.impl.DefaultAnalyticsService
import dev.ide.analytics.impl.SupabaseSink
import dev.ide.core.IdeServicesBackend
import dev.ide.core.ProjectManager
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.platform.log.Log.addSink
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipInputStream
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device bootstrap for the IDE engine, the Android counterpart to :ide-desktop's wiring. ART has no
 * JDK to detect, so the Android SDK's `android.jar` (signatures for `java.*` + `android.*`) ships as an
 * asset, is copied into app storage once, and is fed as each workspace's boot classpath. The whole app
 * directory lives under `<external-files>/codeassist` (projects in `projects/`, one workspace dir each) —
 * app-specific external storage, so a [ProjectsDocumentsProvider] can surface that directory in the system
 * Files app / any file manager without the All-Files-Access permission. A [ProjectManager] creates/opens/lists
 * the projects, so the IDE supports live
 * in-session switching. The IDE starts on the project picker (no project is seeded); everything above that
 * (project model, JDT completion/analysis, indexing, the Android build) is the same `IdeServices` the
 * desktop runs, surfaced through the same `IdeServicesBackend`.
 */
object AndroidIde {

    /** Heavy (file copy + project gen + JDT init) — call off the main thread. */
    fun bootstrap(context: Context): Session {
        val startNs = System.nanoTime()

        // App-specific EXTERNAL storage (Android/data/<pkg>/files/codeassist)
        val home = appHomeDir(context).apply { mkdirs() }
        val manager = createProjectManager(context)

        // Measure the forked-VM R8 heap ceiling once per app version, in the background, and cache it. The
        // Build Runtime settings use it as the heap slider's MAX (user scales down from the real device
        // limit) and the shrinker uses it as the default heap.
        detectR8CeilingAsync(context, manager)

        // Recover projects left in internal storage by a build from before the move to external app storage
        // (issues #1003 / #1024 / #1041 / #1042: projects vanishing from the picker after an update). Runs at
        // most once; non-destructive.
        runCatching { manager.importLegacyProjects() }

        val analytics = buildAnalytics(manager, home)
        // Start with no project open (the picker is shown); opening one from it creates that project's engine
        // on demand. The download cache is shared across projects via the ProjectManager (sharedCachesRoot).
        // Build-process isolation (docs/build-process-isolation.md): always provide the factory that routes a
        // project's build/run to the separate `:build` daemon (RemoteBuildRunner); whether it's actually used
        // is the app-global "Build in a separate process" setting (default ON), checked in
        // IdeServicesBackend.buildRunnerFor. A build OOM then kills only that process, not the IDE.
        val appContext = context.applicationContext
        // Analytics is an application-scoped host service now; register it before the backend resolves it.
        manager.applicationContainer.registerServiceIfAbsent(dev.ide.core.ANALYTICS_SERVICE) { analytics }
        val backend = IdeServicesBackend(
            initial = null, manager = manager,
            buildRunnerFactory = { svc ->
                dev.ide.android.daemon.RemoteBuildRunner(
                    appContext, svc
                )
            },
            // The `:build` daemon posts a foreground-service progress notification; if notifications are off
            // (POST_NOTIFICATIONS denied on API 33+, or disabled in system settings) the isolated build is
            // pointless, so fall back to in-process builds. The first-build prompt (BuildNotificationGate) asks
            // for the grant; this is the live check the runner selection reads. See docs/build-process-isolation.md.
            notificationsAllowed = {
                androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            },
        )
        // Process-wide uncaught-exception handler: report app_crash + surface the non-fatal dialog + keep the
        // app alive (the MainActivity main-thread guard handles the UI looper). See IdeServicesBackend.
        backend.installCrashReporting()
        // cold_start: time the whole on-device bootstrap (asset copy + project load + engine init). Emitted
        // once per launch for users who consented; no-op otherwise. Also serves as the per-launch anchor.
        if (backend.diagnostics.analyticsConsent() == true) {
            backend.diagnostics.track(
                dev.ide.analytics.Events.COLD_START,
                mapOf("duration_ms" to ((System.nanoTime() - startNs) / 1_000_000).toString())
            )
        }

        return Session(backend)
    }

    /**
     * Build the on-device [ProjectManager] — asset provisioning (android.jar / desugar stubs / kotlinc
     * home / debug keystore) plus the ART tool ports (dex runner, APK installer, custom-view runtime,
     * Kotlin-plugin loader) wired through [ProjectManager.onDevice]. Extracted from [bootstrap] so the
     * separate `:build` process (BuildDaemonService, docs/build-process-isolation.md) can stand up the SAME
     * headless engine to run builds, without the UI backend / analytics / crash reporting. Idempotent — the
     * asset copies and the `kotlinc.art.home` system property are per-process, so calling it in the daemon
     * provisions that process correctly even though the main process already did so for its own.
     */
    fun createProjectManager(context: Context): ProjectManager {
        val home = appHomeDir(context).apply { mkdirs() }
        val androidJar = copyAsset(context, "android.jar", File(home, "android.jar"))
        // The Java 9+ desugar stubs (`java.lang.invoke.StringConcatFactory`/`LambdaMetafactory`): `android.jar`
        // omits them, but the compiler emits an `invokedynamic` against `StringConcatFactory` for every string
        // concatenation at source >= 9 (D8 desugars it at build time). Without this on the boot classpath the
        // editor reports a spurious "StringConcatFactory cannot be resolved" on any Java 9+ buffer. Desktop
        // pulls it from build-tools (IdeServices.detectAndroidSdk); on ART it ships as an asset.
        val coreLambdaStubs =
            copyAsset(context, "core-lambda-stubs.jar", File(home, "core-lambda-stubs.jar"))
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
        // android.jar MUST stay first: ProjectManager.onDevice treats bootClasspath.first() as the SDK
        // android.jar. The desugar stubs ride alongside it as the platform.
        val bootClasspath = listOf(androidJar.absolutePath, coreLambdaStubs.absolutePath)
        // Runs a dexed console app on ART (there's no `java` to fork). Prefer a FORKED `dalvikvm` process
        // (fully isolated + truly killable: a runaway loop dies with the process, and the run needs no
        // in-process sandbox); fall back to the in-process DexClassLoader on the rare device with no dalvikvm.
        val forkRunner = ForkedDalvikRunner(context.applicationContext, File(context.cacheDir, "dexrun-fork"))
        val dexRunner: dev.ide.build.engine.DexRunner =
            if (forkRunner.available()) forkRunner else DexClassLoaderRunner(File(context.cacheDir, "dexrun"))
        // Installs + launches a built APK (the android Run) via the system package installer.
        val apkInstaller = ApkInstallerImpl(context)
        // The debug-only in-app log bridge: extract the bundled runtime jar (woven into debug builds) and host
        // the LocalServerSocket it connects to, so a running debug app's logs stream to the IDE's Logcat tab.
        val appLogRuntimeJar = copyAsset(context, "applog-runtime.jar", File(home, "applog-runtime.jar"))
        val appLogChannel = AppLogChannelImpl()
        // Custom user views in the layout preview are DISABLED at this time: rendering them means D8-dexing the
        // user's compiled classes and loading them via DexClassLoader, which Google Play's Device-and-Network-
        // Abuse "DDL" scorer flags. Null → the owned preview shows placeholders for `<com.example.MyView/>`.
        // To re-enable, restore:
        //   DexCustomViewRuntime(context.applicationContext, androidJar.toPath(),
        //       File(context.cacheDir, "preview"), Build.VERSION.SDK_INT)
        val previewRuntime: dev.ide.preview.impl.CustomViewRuntime? = null
        // Loads runtime (non-bundled) Kotlin compiler plugins on ART: D8-dex the plugin classpath + DexClassLoader.
        val kotlinPluginLoader = ArtKotlinPluginLoader(
            androidJar.toPath(),
            File(context.cacheDir, "kotlinc-plugins").toPath(),
            Build.VERSION.SDK_INT,
        )
        // Runs the release/minify R8 pass in a forked dalvikvm with a heap above the app cap (the bundled
        // r8.dex asset is its classpath). Self-falls-back to in-process R8 if forking isn't usable here.
        // The heap comes from the "R8 maximum heap" setting, read lazily from the manager's prefs at build
        // time (a holder breaks the cycle: the shrinker is built before the manager it reads from).
        val managerRef = AtomicReference<ProjectManager?>()
        val settingsPrefix = "settings.${BuiltInSettingsPages.BUILD_RUNTIME}."
        val r8HeapKey = settingsPrefix + BuiltInSettingsPages.R8_MAX_HEAP
        val r8ModeKey = settingsPrefix + BuiltInSettingsPages.R8_MODE
        val r8ModeProvider = { managerRef.get()?.preference(r8ModeKey)?.trim() }
        // The user's heap setting, else the measured device ceiling (so the default matches the slider), else
        // null → the built-in default. Shared by the forked R8 shrinker and the forked D8 merge dexer.
        val r8HeapProvider = {
            val mgr = managerRef.get()
            mgr?.preference(r8HeapKey)?.trim()?.toIntOrNull()
                ?: mgr?.preference(BuiltInSettingsPages.R8_CEILING_PREF)
                    ?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        }
        val r8Shrinker =
            ForkedR8Shrinker(context.applicationContext, r8ModeProvider, r8HeapProvider)
        // The debug-dex memory knobs (Build Runtime page), read lazily like the R8 ones.
        val dexOffHeapKey =
            settingsPrefix + BuiltInSettingsPages.DEX_OFFHEAP_MB
        val dexOffHeapProvider =
            { managerRef.get()?.preference(dexOffHeapKey)?.trim()?.toIntOrNull() }
        val dexMergeBatchKey =
            settingsPrefix + BuiltInSettingsPages.DEX_MERGE_BATCH
        val dexMergeChunkProvider = {
            managerRef.get()?.preference(dexMergeBatchKey)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT
        }
        // "Max concurrent dex forks" (0/absent = auto, sized from device RAM): how many forked merge/archive VMs
        // run at once, batching the per-library merges instead of forking one VM at a time.
        val dexForkConcurrencyKey =
            settingsPrefix + BuiltInSettingsPages.DEX_FORK_CONCURRENCY
        val dexForkConcurrencyProvider =
            { managerRef.get()?.preference(dexForkConcurrencyKey)?.trim()?.toIntOrNull() }
        // "Forward app logs" (Build Runtime page), default on — read lazily like the R8/dex knobs.
        val injectAppLogKey = settingsPrefix + BuiltInSettingsPages.INJECT_APP_LOG
        val appLogEnabledProvider = { managerRef.get()?.preference(injectAppLogKey)?.trim() != "false" }
        // The dex MERGE (debug-path memory peak) forks too, under the same R8 execution / heap settings; the
        // archive step forks above the "Off-heap dexing threshold". The merge batches + parallelizes across
        // forked VMs bounded by the process-wide fork gate (see ForkedD8Dexer / R8ForkSupport).
        val r8MergeDexer = ForkedD8Dexer(
            context.applicationContext,
            r8ModeProvider,
            r8HeapProvider,
            dexOffHeapProvider,
            dexForkConcurrencyProvider
        )
        // Renders the layout with the REAL framework + library views (layoutlib-on-device): reuses the build's
        // aapt2-linked resources + R.jar, dexes the library classpath, inflates real views, draws to a bitmap.
        // Runs in the separate `:preview` process (RemoteRealViewRuntime) when the "Build in a separate process"
        // setting is on (default) — isolating arbitrary library/user View code, with in-process fallback —
        // governed by the same toggle as the build daemon (read lazily via the manager).
        val separateProcessKey =
            settingsPrefix + BuiltInSettingsPages.SEPARATE_PROCESS
        val realViewRuntime = dev.ide.preview.realview.RemoteRealViewRuntime(
            context.applicationContext,
            androidJar.toPath(),
            File(context.cacheDir, "realview"),
            Build.VERSION.SDK_INT,
            separateProcessEnabled = {
                managerRef.get()?.preference(separateProcessKey)?.trim() != "false"
            },
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
        val legacyDataDirs =
            listOf(legacyProjectsDir, legacyInternalHome).filter { java.nio.file.Files.exists(it) }
        return ProjectManager.onDevice(
            projectsRoot, bootClasspath, nativeLibDir, debugKeystore.toPath(),
            storageRoot = externalHome(context).toPath(),
            legacyDataDirs = legacyDataDirs,
            dexRunner = dexRunner,
            deviceApiLevel = Build.VERSION.SDK_INT,
            apkInstaller = apkInstaller,
            appLogRuntimeJar = appLogRuntimeJar.toPath(),
            appLogChannel = appLogChannel,
            appLogEnabledProvider = appLogEnabledProvider,
            customViewRuntime = previewRuntime,
            realViewRuntime = realViewRuntime,
            kotlinPluginLoader = kotlinPluginLoader,
            r8Shrinker = r8Shrinker,
            r8MergeDexer = r8MergeDexer,
            mergeChunkProvider = dexMergeChunkProvider,
        ).also { managerRef.set(it) }
    }

    /**
     * Build the analytics service from the baked-in Supabase config. Returns the no-op service when no
     * endpoint is configured (a fork building without a key) so the rest of the app is unaffected. The
     * install id is a random UUID persisted once in prefs (not tied to any account); the session id is fresh
     * per launch. The service starts gated on the stored consent and collects nothing until it's granted.
     */
    private fun buildAnalytics(
        manager: ProjectManager, home: File
    ): dev.ide.analytics.AnalyticsService {
        val url = BuildConfig.ANALYTICS_URL
        val key = BuildConfig.ANALYTICS_KEY
        if (url.isBlank() || key.isBlank()) return dev.ide.analytics.NoopAnalyticsService

        val installId = manager.preference("analytics.install.id") ?: UUID.randomUUID().toString()
            .also { manager.setPreference("analytics.install.id", it) }
        val device = DeviceInfo(
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = BuildConfig.VERSION_CODE,
            osApi = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL ?: "",
            deviceManufacturer = Build.MANUFACTURER ?: "",
            abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "",
            locale = Locale.getDefault().toLanguageTag(),
        )
        val service = DefaultAnalyticsService(
            installId = installId,
            sessionId = UUID.randomUUID().toString(),
            device = device,
            sink = SupabaseSink(url, key),
            initialConsent = manager.preference("analytics.consent") == "granted",
            queueFile = File(home, "analytics-queue.txt").toPath(),
        )
        // Bridge the logging facade to analytics: caught ERROR logs become scrubbed `error_logged` events
        // (no messages/paths). No-ops while the service is disabled, and starts working on consent.
        addSink(AnalyticsLogSink(service))
        return service
    }

    /** Provision just the bundled `android.jar` for a process that needs only it (the `:preview` render
     *  daemon), without the full [createProjectManager] engine setup. Idempotent (marker-guarded copy). */
    fun provisionAndroidJar(context: Context): File =
        copyAsset(context, "android.jar", File(appHomeDir(context), "android.jar"))

    /** App-specific external storage base (`Android/data/<pkg>/files`), or internal `filesDir` if external
     *  isn't currently mounted. Resolved the same way by [bootstrap] and [ProjectsDocumentsProvider] so both
     *  see one projects directory. */
    fun externalHome(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** The whole CodeAssist app directory (`<external-files>/codeassist`): projects, the SDK `android.jar`,
     *  the debug keystore, the kotlinc home, shared caches. This is the root surfaced to file managers. */
    fun appHomeDir(context: Context): File = File(externalHome(context), "codeassist")

    /** The on-disk projects directory (`<external-files>/codeassist/projects`). */
    fun projectsDir(context: Context): File = File(appHomeDir(context), "projects")

    /** Measure (once per app version, in the background) the largest heap a forked VM grants R8 on this device
     *  and cache it in [BuiltInSettingsPages.R8_CEILING_PREF] (`0` = forking unavailable). The settings UI uses
     *  it as the heap slider's MAX and the shrinker as its default heap. Forks a few short-lived VMs, so it
     *  runs off the main thread; re-measures only when the app updates (a new APK may carry a new R8). */
    private fun detectR8CeilingAsync(context: Context, manager: ProjectManager) {
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        if (manager.preference(R8_CEILING_STAMP_PREF) == stamp) return
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val ceiling = R8ForkSupport.detectCeiling(appContext) ?: 0
                manager.setPreference(
                    BuiltInSettingsPages.R8_CEILING_PREF, ceiling.toString()
                )
                manager.setPreference(R8_CEILING_STAMP_PREF, stamp)
            }
        }.apply { isDaemon = true; name = "r8-ceiling-detect" }.start()
    }

    private const val R8_CEILING_STAMP_PREF = "r8.detectedCeilingStamp"

    /**
     * Copy a bundled asset into app storage, re-extracting when the APK has been updated since the last
     * copy (assets are read-only in the APK). The re-extract-on-update check is essential: app storage lives
     * under the external files dir, which survives an APK update, so a copy-once would pin the FIRST version
     * of every asset forever. That stranded a stale `debug.keystore` (the pre-fix one keytool wrote with an
     * ART-unreadable HmacPBESHA256 MAC) even after shipping a new legacy-PKCS12 asset, so on-device signing
     * kept failing with "PKCS12 key store mac invalid". A fresh copy's mtime is after the install, so a
     * subsequent launch with no new update sees it as current.
     */
    private fun copyAsset(context: Context, name: String, dest: File): File {
        // Re-extract only when the app has been updated (a new APK may carry a new asset), tracked by a marker
        // holding the package's lastUpdateTime. The previous guard compared dest.lastModified() to lastUpdateTime,
        // but a freshly-written file on this device's emulated external storage reports an unreliable mtime, so
        // the guard fired every launch and re-copied with a fresh mtime — which re-keyed (and so re-indexed)
        // android.jar on every cold start. The marker is mtime-independent. Mirrors [provisionKotlincHome].
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        val marker = File(dest.parentFile, "${dest.name}.provisioned")
        val upToDate =
            dest.exists() && dest.length() > 0L && marker.exists() && marker.readText() == stamp
        if (!upToDate) {
            dest.parentFile?.mkdirs()
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            runCatching { marker.writeText(stamp) }
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

    /** The UI-port adapter; [backend] is held so the Activity can close the active engine on teardown.
     *  No engine is created until the user opens a project from the picker (the lazy-start path). */
    class Session(val backend: IdeServicesBackend)
}
