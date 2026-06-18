package dev.ide.core

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.AndroidSdkInstaller
import dev.ide.android.support.tools.HttpSdkNetFetcher
import dev.ide.android.support.tools.SdkNetFetcher
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
 * On a successful install the host's analyzers are invalidated so the new sources take effect.
 */
class SdkManagerService(
    private val workspaceRoot: Path,
    private val onChanged: () -> Unit,
    private val fetcher: SdkNetFetcher = HttpSdkNetFetcher,
) {
    private val platformDir: Path = workspaceRoot.resolve(".platform")
    private val downloadsDir: Path = platformDir.resolve("sdk-downloads")
    private val jdk = JdkManager(platformDir, fetcher)

    /** Background scope for downloads — survives screen navigation, cancelled in [dispose]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Ids the user asked to cancel; checked from the blocking download loop (which can't see coroutine cancel). */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(UiSdkManagerState())
    val state: StateFlow<UiSdkManagerState> = _state.asStateFlow()

    /** The src.zip from a previously downloaded JDK (for the analyzer to attach), or null. */
    fun jdkSourceOverride(): Path? = jdk.overrideSrcZip()

    fun jdkInfo(): UiJdkInfo = jdk.info().let { UiJdkInfo(it.home, it.version, it.srcZip) }

    /** Where Android packages install: the detected SDK, else a conventional per-OS location (created on use). */
    fun androidSdkRoot(): Path = AndroidSdk.findSdkRoot(workspaceRoot) ?: defaultAndroidSdkRoot()

    /** List the installable Android **source** packages, each flagged [UiSdkPackage.installed]/
     *  [UiSdkPackage.incomplete]. The SDK Manager is for sources/docs only, so platforms/build-tools/
     *  command-line tools are intentionally excluded. Empty when offline. */
    suspend fun androidPackages(): List<UiSdkPackage> = withContext(Dispatchers.IO) {
        val root = androidSdkRoot()
        val installed = runCatching { AndroidSdkInstaller.installedPackages(root) }.getOrDefault(emptySet())
        val incomplete = runCatching { AndroidSdkInstaller.incompletePackages(root) }.getOrDefault(emptySet())
        AndroidSdkInstaller.fetchPackages(fetcher)
            .filter { it.category == AndroidSdkInstaller.Category.SOURCES }
            .map { UiSdkPackage(it.path, it.displayName, it.category.name, it.sizeBytes, it.path in installed, it.archiveUrl != null, it.path in incomplete) }
    }

    /** Start downloading one Android package by its sdkmanager id (`sources;android-34`, `platforms;android-34`,
     *  …) on the background scope. Returns immediately; progress streams on [state]. */
    fun installAndroidPackage(path: String): String {
        if (jobs.containsKey(path)) return "Already downloading $path."
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

    fun dispose() {
        scope.cancel()
        jobs.clear()
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
                err == null -> { finish(id, "DONE", "Installed"); onChanged() }
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
        val home = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> Paths.get(home, "Library", "Android", "sdk")
            os.contains("win") -> Paths.get(home, "AppData", "Local", "Android", "Sdk")
            else -> Paths.get(home, "Android", "Sdk")
        }
    }
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
