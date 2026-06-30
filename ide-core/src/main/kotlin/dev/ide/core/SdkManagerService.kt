package dev.ide.core

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.AndroidSdkInstaller
import dev.ide.android.support.tools.HttpSdkNetFetcher
import dev.ide.android.support.tools.SdkNetFetcher
import dev.ide.platform.Disposable
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkDownload
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * The SDK / toolchain manager — it acquires **sources and documentation** for the editor (SDK platform
 * sources, JDK `src.zip`) so javadoc, parameter names, and go-to-source resolve against the real APIs.
 *
 * Downloads run on a long-lived [scope] (not the caller's), so they keep going after the SDK Manager screen
 * is closed — the UI observes the shared [state] (a per-item queue) exactly like the Dependencies screen
 * observes its resolve state. Interrupted downloads are resumable (a per-id archive cache + HTTP Range) and
 * interrupted installs are detectable (an `.installing` marker), so retrying repairs rather than restarts.
 * On a successful install the registered change listeners fire so the active engine's analyzers are
 * invalidated and the new sources take effect.
 *
 * APPLICATION-scoped: one shared instance (its download queue + the resumable cache) serves every project,
 * so the Settings & Tools hub can drive it from the project picker with no project open. Because the
 * instance outlives any single engine, the per-engine "invalidate my analyzers" reaction is a [Disposable]
 * change listener the active engine adds on open and drops on close (see [addChangeListener]) — not a
 * constructor-captured callback bound to one engine. The owning [dev.ide.platform.ServiceContainer]
 * (the application container) disposes it at app shutdown.
 */
class SdkManagerService(
    private val workspaceRoot: Path,
    private val fetcher: SdkNetFetcher = HttpSdkNetFetcher,
    sharedRoot: Path? = null,
) : Disposable {
    // SDK sources, the JDK src.zip, and the resumable download cache are toolchain artifacts, not project
    // files, so they live under the shared root (the host's home dir) when one is supplied: installed once
    // and reused across every project. Without a shared root (e.g. the desktop demo) they fall back per-workspace.
    private val platformDir: Path = (sharedRoot ?: workspaceRoot).resolve(".platform")
    private val downloadsDir: Path = platformDir.resolve("sdk-downloads")
    private val jdk = JdkManager(platformDir, fetcher)

    /** Background scope for downloads — survives screen navigation, cancelled in [dispose]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Ids the user asked to cancel; checked from the blocking download loop (which can't see coroutine cancel). */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(UiSdkManagerState())
    val state: StateFlow<UiSdkManagerState> = _state.asStateFlow()

    /** Reactions to a successful install (e.g. the active engine re-attaching the new SDK sources to its
     *  analyzers + index). Identity-keyed so each engine adds/removes its own lambda over its lifecycle. */
    private val changeListeners = ConcurrentHashMap.newKeySet<() -> Unit>()

    /** Subscribe [listener] to fire after a successful install. Returns a [Disposable] that unsubscribes it. */
    fun addChangeListener(listener: () -> Unit): Disposable {
        changeListeners.add(listener)
        return Disposable { changeListeners.remove(listener) }
    }

    private fun notifyChanged() = changeListeners.forEach { runCatching { it() } }

    /** The src.zip from a previously downloaded JDK (for the analyzer to attach), or null. */
    fun jdkSourceOverride(): Path? = jdk.overrideSrcZip()

    fun jdkInfo(): UiJdkInfo = jdk.info().let { UiJdkInfo(it.home, it.version, it.srcZip) }

    /** Where Android packages install: the detected SDK, else a conventional per-OS location (created on use). */
    fun androidSdkRoot(): Path = AndroidSdk.findSdkRoot(workspaceRoot) ?: defaultAndroidSdkRoot()

    /** List the installable Android **source** packages, each flagged [UiSdkPackage.installed]/
     *  [UiSdkPackage.incomplete]. The SDK Manager is for sources/docs only ([AndroidSdkInstaller] fetches no
     *  other category), so platforms/build-tools/command-line tools never appear. Empty when offline. */
    suspend fun androidPackages(): List<UiSdkPackage> = withContext(Dispatchers.IO) {
        val root = androidSdkRoot()
        val installed = runCatching { AndroidSdkInstaller.installedPackages(root) }.getOrDefault(emptySet())
        val incomplete = runCatching { AndroidSdkInstaller.incompletePackages(root) }.getOrDefault(emptySet())
        AndroidSdkInstaller.fetchPackages(fetcher)
            .map { UiSdkPackage(it.path, it.displayName, it.category.name, it.sizeBytes, it.path in installed, it.archiveUrl != null, it.path in incomplete) }
    }

    /** Start downloading one Android **source** package by its sdkmanager id (`sources;android-34`) on the
     *  background scope. Returns immediately; progress streams on [state]. */
    fun installAndroidPackage(path: String): String {
        if (jobs.containsKey(path)) return "Already downloading $path."
        // Sources only: the SDK Manager never offers anything else, so reject a stale/foreign id outright.
        if (!path.startsWith("sources;")) return "Only SDK sources can be installed."
        launchDownload(path, path) {
            val pkg = withContext(Dispatchers.IO) { AndroidSdkInstaller.fetchPackages(fetcher).firstOrNull { it.path == path } }
                ?: return@launchDownload "Package $path not found in the repository."
            update(path) { it.copy(label = pkg.displayName) }
            val root = androidSdkRoot()
            runCatching { Files.createDirectories(root) }
            AndroidSdkInstaller.install(
                pkg, root, downloadsDir, fetcher,
                onProgress = { read, total ->
                    abortIfCancelled(path)
                    update(path) { it.copy(status = "DOWNLOADING", fraction = frac(read, total), detail = bytes(read, total)) }
                },
                onStage = { stage -> update(path) { it.copy(status = stage, fraction = if (stage == "DOWNLOADING") it.fraction else -1.0, detail = if (stage == "DOWNLOADING") it.detail else "") } },
            )
        }
        return "Downloading $path…"
    }

    /** Download Temurin JDK [feature] on the background scope and keep its sources for the editor. */
    fun downloadJdkSources(feature: Int): String {
        val id = "jdk-$feature"
        if (jobs.containsKey(id)) return "Already downloading JDK $feature."
        launchDownload(id, "JDK $feature sources") {
            withContext(Dispatchers.IO) {
                jdk.downloadJdkSources(feature) { read, total ->
                    abortIfCancelled(id)
                    update(id) { it.copy(status = "DOWNLOADING", fraction = frac(read, total), detail = bytes(read, total)) }
                }
            }
        }
        return "Downloading JDK $feature sources…"
    }

    /** Cancel an in-flight download by id (the package path, or `jdk-<feature>`). The flag stops the blocking
     *  download at its next progress tick; the partial archive is kept so a retry resumes it. */
    fun cancelSdkDownload(id: String) {
        if (jobs.containsKey(id)) {
            cancelled.add(id)
            update(id) { it.copy(detail = "Cancelling…") }
        }
    }

    /** Drop the finished (done/failed) entries from the queue. */
    fun clearSdkDownloads() = _state.update { it.copy(downloads = it.downloads.filterNot { d -> d.status == "DONE" || d.status == "FAILED" }).withAggregate() }

    /** Status of the Android platform sources for inlay/completion docs, or null when there's no Android SDK.
     *  APPLICATION-scoped (uses the shared [androidSdkRoot]), so the picker's hub reports it with no project. */
    fun androidSourcesInfo(): AndroidSourcesInfo? {
        val sdkRoot = AndroidSdk.findSdkRoot(workspaceRoot) ?: return null
        val sdk = AndroidSdk.detect(sdkRoot) ?: return null
        val platform = sdk.androidJar.parent?.fileName?.toString() ?: return null // android-NN
        // Same-major sources count as installed: the editor resolves them by major API level, so an exact
        // `sources/android-36` isn't required when `sources/android-36.1` is present (and vice-versa).
        val installed = AndroidSdk.platformSourcesDir(sdkRoot, platform) != null
        return AndroidSourcesInfo(platform, installed, downloadable = findSdkmanager(sdkRoot) != null)
    }

    /**
     * Download the Android platform sources via `sdkmanager` (desktop only). Pipes license acceptance and
     * bounds the run with a timeout so it cannot hang the IDE. On success the change listeners fire so the
     * active engine re-attaches the new sources. Returns a human-readable status.
     */
    fun downloadAndroidSources(): String {
        val sdkRoot = AndroidSdk.findSdkRoot(workspaceRoot) ?: return "No Android SDK found."
        val sdk = AndroidSdk.detect(sdkRoot) ?: return "No installed Android platform."
        val platform = sdk.androidJar.parent?.fileName?.toString() ?: return "Couldn't determine the platform."
        if (AndroidSdk.platformSourcesDir(sdkRoot, platform) != null) return "Sources for $platform are already installed."
        val sdkmanager = findSdkmanager(sdkRoot)
            ?: return "sdkmanager not found — install the sources via Android Studio's SDK Manager (SDK Platforms → Sources for Android $platform)."
        return runCatching {
            val proc = ProcessBuilder(sdkmanager.toString(), "sources;$platform")
                .directory(sdkRoot.toFile()).redirectErrorStream(true).start()
            proc.outputStream.bufferedWriter().use { w -> repeat(50) { runCatching { w.write("y\n"); w.flush() } } }
            val done = proc.waitFor(4, java.util.concurrent.TimeUnit.MINUTES)
            if (!done) {
                proc.destroyForcibly(); return "Timed out downloading sources for $platform."
            }
            if (proc.exitValue() == 0) {
                notifyChanged() // pick up the freshly-installed sources (active engine re-attaches + reindexes)
                "Installed sources for $platform."
            } else "sdkmanager failed (exit ${proc.exitValue()}) installing sources for $platform."
        }.getOrElse { "Couldn't run sdkmanager: ${it.message}" }
    }

    /** Locate `sdkmanager` under the SDK (cmdline-tools preferred, then legacy tools). */
    private fun findSdkmanager(sdkRoot: Path): Path? {
        val isWin = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val exe = if (isWin) "sdkmanager.bat" else "sdkmanager"
        val candidates = buildList {
            add(sdkRoot.resolve("cmdline-tools").resolve("latest").resolve("bin").resolve(exe))
            val cmdlineToolsDir = sdkRoot.resolve("cmdline-tools")
            if (Files.isDirectory(cmdlineToolsDir)) {
                Files.list(cmdlineToolsDir).use { s -> s.forEach { add(it.resolve("bin").resolve(exe)) } }
            }
            add(sdkRoot.resolve("tools").resolve("bin").resolve(exe))
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    override fun dispose() {
        scope.cancel()
        jobs.clear()
        changeListeners.clear()
    }

    // ---- internals ----

    /** Throws to abort the blocking download loop once the user has cancelled [id]. */
    private fun abortIfCancelled(id: String) { if (id in cancelled) throw CancellationException("cancelled") }

    /** Run [work] (returns null on success, else an error message) as a background download tracked under [id]. */
    private fun launchDownload(id: String, label: String, work: suspend () -> String?) {
        cancelled.remove(id)
        update(id) { it.copy(label = label, status = "DOWNLOADING", fraction = -1.0, detail = "Starting…") }
        val job = scope.launch {
            val err = try {
                work()
            } catch (c: CancellationException) {
                null // handled below via the cancelled flag
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
            when {
                id in cancelled -> finish(id, "FAILED", "Cancelled")
                err == null -> { finish(id, "DONE", "Installed"); notifyChanged() }
                else -> finish(id, "FAILED", err)
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job); cancelled.remove(id) }
    }

    private fun update(id: String, f: (UiSdkDownload) -> UiSdkDownload) = _state.update { st ->
        val list = st.downloads.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val cur = if (idx >= 0) list[idx] else UiSdkDownload(id = id, label = id, status = "DOWNLOADING")
        val next = f(cur)
        if (idx >= 0) list[idx] = next else list.add(next)
        st.copy(downloads = list).withAggregate()
    }

    private fun finish(id: String, status: String, detail: String) = update(id) { it.copy(status = status, fraction = if (status == "DONE") 1.0 else -1.0, detail = detail) }

    private fun frac(read: Long, total: Long): Double = if (total > 0) read.toDouble() / total else -1.0
    private fun bytes(read: Long, total: Long): String = mb(read) + if (total > 0) " / ${mb(total)}" else ""
    private fun mb(bytes: Long): String = if (bytes <= 0) "0 MB" else "%.1f MB".format(bytes / 1_048_576.0)

    private fun defaultAndroidSdkRoot(): Path {
        // On ART there is no usable user.home (it resolves to "/"), so the conventional "$home/Android/Sdk"
        // becomes "/Android/Sdk" at the read-only filesystem root. Keep installs inside the writable shared
        // platform dir instead (where the JDK and downloads already live), so they are shared across projects.
        if (isAndroidRuntime) return platformDir.resolve("android-sdk")
        val home = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> Paths.get(home, "Library", "Android", "sdk")
            os.contains("win") -> Paths.get(home, "AppData", "Local", "Android", "Sdk")
            else -> Paths.get(home, "Android", "Sdk")
        }
    }

    /** True on Android's runtime (ART/Dalvik), where user.home is not a writable location. */
    private val isAndroidRuntime: Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)
}

/** Recompute the aggregate [busy]/[message]/[fraction] header from the per-item queue. */
private fun UiSdkManagerState.withAggregate(): UiSdkManagerState {
    val active = downloads.filter { it.status != "DONE" && it.status != "FAILED" }
    if (active.isEmpty()) return copy(busy = false, message = "", fraction = -1.0)
    val message = if (active.size == 1) "${active[0].status.lowercase().replaceFirstChar { it.uppercase() }} ${active[0].label}…"
                  else "${active.size} downloads in progress"
    val fraction = if (active.size == 1) active[0].fraction else -1.0
    return copy(busy = true, message = message, fraction = fraction)
}
