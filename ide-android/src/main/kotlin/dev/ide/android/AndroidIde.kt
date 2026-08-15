package dev.ide.android

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import dev.ide.analytics.AnalyticsEvent
import dev.ide.analytics.DeviceInfo
import dev.ide.analytics.EventCategory
import dev.ide.analytics.Events
import dev.ide.build.jvm.run.VmProgramInterpreter
import dev.ide.analytics.impl.AnalyticsLogSink
import dev.ide.analytics.impl.DefaultAnalyticsService
import dev.ide.analytics.impl.SupabaseSink
import dev.ide.core.ANALYTICS_SERVICE
import dev.ide.core.IdeServicesBackend
import dev.ide.core.ProjectManager
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.platform.log.Log
import dev.ide.platform.log.Log.addSink
import java.io.File
import java.io.IOException
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

        // Pin the process word-size BEFORE any engine is created. On a 32-bit ARM process the engine collapses
        // background index concurrency to stop provoking the 32-bit-ART torn-reference SIGSEGV (see RuntimeInfo).
        // `android.os.Process.is64Bit()` reports THIS process, not merely device capability (API 23+; minSdk 26).
        dev.ide.platform.RuntimeInfo.set32Bit(!android.os.Process.is64Bit())

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

        // Crash breadcrumb: the 32-bit-ART SIGSEGV is a native fault, uncatchable in-process — so read what the
        // engine was doing if the PREVIOUS process died natively (via the OS exit reason), THEN arm the file for
        // this session's engine to write into. This is our only signal for that crash. See EngineBreadcrumb.
        val crumbFile = File(home, "last-engine-op.log")
        dev.ide.platform.EngineBreadcrumb.init(crumbFile.toPath())
        reportPreviousNativeCrash(context, manager, analytics, dev.ide.platform.EngineBreadcrumb.readLast())

        // Start with no project open (the picker is shown); opening one from it creates that project's engine
        // on demand. The download cache is shared across projects via the ProjectManager (sharedCachesRoot).
        // Build-process isolation (docs/build-process-isolation.md): always provide the factory that routes a
        // project's build/run to the separate `:build` daemon (RemoteBuildRunner); whether it's actually used
        // is the app-global "Build in a separate process" setting (default ON), checked in
        // IdeServicesBackend.buildRunnerFor. A build OOM then kills only that process, not the IDE.
        val appContext = context.applicationContext
        // Analytics is an application-scoped host service now; register it before the backend resolves it.
        manager.applicationContainer.registerServiceIfAbsent(ANALYTICS_SERVICE) { analytics }
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
                NotificationManagerCompat.from(appContext).areNotificationsEnabled()
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
        // Runs a console app by INTERPRETING its compiled bytecode on the VM — no dexing, no dynamic class
        // loading of the user's/libraries' code. The peer factory dexes the small generated peer classes the
        // VM needs when an interpreted object is handed to real platform code (a Comparator, a Runnable).
        val programInterpreter = VmProgramInterpreter(peerFactory = DexPeerFactory())
        // Installs + launches a built APK (the android Run) via the system package installer.
        val apkInstaller = ApkInstallerImpl(context)
        // The debug-only in-app log bridge: extract the bundled runtime jar (woven into debug builds); the
        // bridge inside the built app binds the IDE's exported AppLogSinkService over Binder and pushes its
        // logs to the IDE's Logcat tab. Best-effort — a missing/failed asset must NEVER stop the IDE from
        // starting; null just disables app-log forwarding (the Logcat tab stays empty).
        val appLogRuntimeJar = runCatching {
            copyAsset(context, "applog-runtime.jar", File(home, "applog-runtime.jar"))
        }.getOrNull()
        val appLogChannel = AppLogChannelImpl()
        // The legacy dex-based custom-view seam is gone (it D8-dexed the user's classes onto a DexClassLoader,
        // which Google Play's Device-and-Network-Abuse "DDL" scorer flags). Custom library AND project views are
        // now INTERPRETED by the real-view runtime's bytecode VM (see AndroidRealViewRuntime / VmViewFactory), so
        // this owned-preview seam stays null.
        val previewRuntime: dev.ide.preview.impl.CustomViewRuntime? = null
        // Loads runtime (non-bundled) Kotlin compiler plugins on ART: D8-dex the plugin classpath + DexClassLoader.
        val kotlinPluginLoader = ArtKotlinPluginLoader(
            androidJar.toPath(),
            File(context.cacheDir, "kotlinc-plugins").toPath(),
            // D8 (r8 8.13.19) supports min-api up to 36; a newer device (SDK_INT 37+) would trip a
            // "not supported by this compiler" warning, so cap it. The dexed plugin/processor code still runs
            // on the device — min-api only bounds desugaring, and 36 is a safe floor for anything >= 36.
            minOf(Build.VERSION.SDK_INT, 36),
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
        // When external storage is unusable, [externalHome] falls back to internal `filesDir`, so the ACTIVE
        // home (`home`, above) IS `filesDir/codeassist` = legacyInternalHome. Never list the active home as a
        // legacy source — it would import a directory into itself. (In the normal external case they differ.)
        val activeHome = home.toPath()
        val legacyDataDirs = listOf(legacyProjectsDir, legacyInternalHome)
            .filter { java.nio.file.Files.exists(it) && it != activeHome }
        return ProjectManager.onDevice(
            projectsRoot, bootClasspath, nativeLibDir, debugKeystore.toPath(),
            storageRoot = externalHome(context).toPath(),
            legacyDataDirs = legacyDataDirs,
            programInterpreter = programInterpreter,
            deviceApiLevel = Build.VERSION.SDK_INT,
            apkInstaller = apkInstaller,
            appLogRuntimeJar = appLogRuntimeJar?.toPath(),
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
    /** The newest process-exit record already reported, so the OS's multi-launch exit history is not re-sent. */
    private const val PREF_NATIVE_CRASH_REPORTED = "crash.native.reported.at"

    /** How far back a process-exit record may be and still be reported. The event carries the version running
     *  now, so an older record would be attributed to the wrong release. */
    private const val NATIVE_CRASH_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * If a PREVIOUS process died of a native crash (the ART SIGSEGV; see [dev.ide.platform.RuntimeInfo]; seen on
     * 32-bit AND 64-bit devices), report it: to the Logs viewer, and for opt-in users as a CRASH analytics event.
     * Needs the OS exit reason (`ActivityManager.getHistoricalProcessExitReasons`, API 30+; the crashing
     * Android-12 devices have it); below API 30 it can't confirm a crash, so it stays silent. Never throws into
     * bootstrap.
     *
     * Two sources are combined, because neither alone localises the fault:
     *  - the engine breadcrumb [prev], which names the editor op the engine most recently STARTED. It records
     *    only op starts, so an op that finished long before the crash still reads as the last one; `since_op_ms`
     *    ships the gap between that op and the death so a stale crumb is recognisable rather than believed.
     *  - the OS tombstone ([dev.ide.platform.NativeTombstone]), which names the thread that actually faulted,
     *    the signal and its code, the fault address and the native backtrace. This is what distinguishes a fault
     *    inside the editor engine from one on a render/GC/JIT thread that the breadcrumb cannot see.
     *
     * Every property is a signal, a symbol, an address or a thread name; paths are reduced to basenames by the
     * parser, so no file name or source content is reported.
     *
     * Each exit record is reported once ([PREF_NATIVE_CRASH_REPORTED]), since the OS keeps it for many launches,
     * and records older than [NATIVE_CRASH_MAX_AGE_MS] are dropped so an update does not import old history
     * under the new version's name.
     */
    private fun reportPreviousNativeCrash(
        context: Context,
        manager: ProjectManager,
        analytics: dev.ide.analytics.AnalyticsService,
        prev: dev.ide.platform.EngineBreadcrumb.Crumb?,
    ) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
            // Read a window of records rather than just the newest: the `:build` and `:preview` processes exit
            // routinely, and with a window of one their exits hide a native death of the IDE process.
            val reported = manager.preference(PREF_NATIVE_CRASH_REPORTED)?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
                .filter {
                    it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == android.app.ApplicationExitInfo.REASON_SIGNALED
                }
                .filter { it.timestamp > reported && now - it.timestamp < NATIVE_CRASH_MAX_AGE_MS }
                .sortedBy { it.timestamp }
                .takeLast(5)
            if (fresh.isEmpty()) return
            manager.setPreference(PREF_NATIVE_CRASH_REPORTED, fresh.last().timestamp.toString())
            fresh.forEach { reportNativeExit(it, analytics, prev) }
        }
    }

    /** Log and track one native exit record, with the breadcrumb attached when it is recent enough to describe
     *  this death rather than an earlier one. */
    private fun reportNativeExit(
        exit: android.app.ApplicationExitInfo,
        analytics: dev.ide.analytics.AnalyticsService,
        prev: dev.ide.platform.EngineBreadcrumb.Crumb?,
    ) {
        val sinceOp = prev?.let { exit.timestamp - it.epochMillis }
        // A crumb from long before the death describes an unrelated session, not this crash.
        val crumb = if (sinceOp != null && sinceOp > -60_000L && sinceOp < 10 * 60_000L) prev else null
        val tomb = runCatching {
            exit.traceInputStream?.use { dev.ide.platform.NativeTombstone.parse(it) }
        }.getOrNull()

        Log.logger("ide.crash").warn(
            "Recovered from a native crash in a previous session of '${exit.processName}'. " +
                "OS exit: ${exit.description} (reason=${exit.reason}, status=${exit.status}). " +
                (tomb?.let {
                    "Fault: ${it.signal}/${it.signalCode} at 0x${java.lang.Long.toHexString(it.faultAddress ?: 0)} " +
                        "on thread '${it.faultingThread}' (${it.arch}). ${it.cause ?: ""} ${it.topFrames(4)}. "
                } ?: "No tombstone available. ") +
                (crumb?.let {
                    "Engine op last started: '${it.op}' ${sinceOp}ms before the death " +
                        "(index building: ${it.indexBuilding})."
                } ?: "No recent engine breadcrumb.")
        )
        analytics.track(
            AnalyticsEvent(
                Events.APP_CRASH,
                EventCategory.CRASH,
                buildMap {
                    put("kind", "native")
                    put("exit_reason", exit.reason.toString())
                    put("exit_status", exit.status.toString())
                    // The process that died: the IDE, or one of the `:build` / `:preview` children.
                    put("process", exit.processName.substringAfter(':', missingDelimiterValue = "main"))
                    put("importance", exit.importance.toString())
                    put("pss_kb", exit.pss.toString())
                    put("rss_kb", exit.rss.toString())
                    crumb?.let {
                        put("engine_lane", it.op)
                        put("thread", it.thread)
                        put("index_building", it.indexBuilding.toString())
                        put("since_op_ms", sinceOp.toString())
                    }
                    tomb?.let {
                        it.arch?.let { v -> put("arch", v) }
                        it.signal?.let { v -> put("signal", v) }
                        it.signalCode?.let { v -> put("signal_code", v) }
                        it.faultAddress?.let { v -> put("fault_addr", "0x" + java.lang.Long.toHexString(v)) }
                        // The thread that actually faulted, as opposed to the one that wrote the breadcrumb.
                        it.faultingThread?.let { v -> put("fault_thread", v) }
                        it.cause?.let { v -> put("cause", v) }
                        it.abortMessage?.let { v -> put("abort_msg", v) }
                        it.uptimeSeconds?.let { v -> put("uptime_s", v.toString()) }
                        it.topFrames(5).take(500).ifEmpty { null }?.let { v -> put("native_frames", v) }
                    }
                },
            )
        )
    }

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

    /**
     * App-specific external storage base (`Android/data/<pkg>/files`), or the ALWAYS-available internal
     * `filesDir` when external storage isn't usable. Resolved the same way by [bootstrap] and
     * [ProjectsDocumentsProvider] so both see one projects directory.
     *
     * `getExternalFilesDir(null)` returns a non-null path even when the underlying volume is **unmounted or
     * unwritable** — a removed/ejected SD card, the app installed on removable storage that isn't present, or
     * storage simply not ready this early in a cold start. Writing into that stale path then fails with
     * `ENOENT` (the `codeassist/android.jar` startup crash). So don't just null-check: require the volume to be
     * `MEDIA_MOUNTED` AND actually creatable/writable, else fall back to internal storage (which the app already
     * treats as a valid home — see `legacyInternalHome` / `importLegacyProjects`).
     */
    fun externalHome(context: Context): File {
        val external = context.getExternalFilesDir(null)
        if (external != null &&
            Environment.getExternalStorageState(external) == Environment.MEDIA_MOUNTED &&
            (external.isDirectory || external.mkdirs()) &&
            external.canWrite()
        ) {
            return external
        }
        return context.filesDir
    }

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
            // Ensure the target directory exists BEFORE the write. On unusable external storage (unmounted SD,
            // storage not ready) mkdirs fails and `dest.outputStream()` would throw a bare `ENOENT` on the
            // path; [externalHome] already steers off a dead volume, so this is a clear last-line diagnostic.
            val parent = dest.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Cannot create app storage directory ${parent.absolutePath} (storage unavailable?)")
            }
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() <= 0L) throw IOException("Provisioned asset '$name' is empty at ${dest.absolutePath}")
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
