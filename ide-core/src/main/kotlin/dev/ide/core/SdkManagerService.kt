package dev.ide.core

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.AndroidSdkInstaller
import dev.ide.android.support.tools.HttpSdkNetFetcher
import dev.ide.android.support.tools.SdkNetFetcher
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orchestrates the Android SDK package downloader ([AndroidSdkInstaller]) and the [JdkManager] behind one
 * progress [state]. Network/disk work runs off the main thread; on success the host's analyzers are
 * invalidated so freshly-installed sources/platforms take effect.
 */
class SdkManagerService(
    private val workspaceRoot: Path,
    private val onChanged: () -> Unit,
    private val fetcher: SdkNetFetcher = HttpSdkNetFetcher,
) {
    private val platformDir: Path = workspaceRoot.resolve(".platform")
    private val jdk = JdkManager(platformDir, fetcher)

    private val _state = MutableStateFlow(UiSdkManagerState())
    val state: StateFlow<UiSdkManagerState> = _state.asStateFlow()

    /** The src.zip from a previously downloaded JDK (for the analyzer to attach), or null. */
    fun jdkSourceOverride(): Path? = jdk.overrideSrcZip()

    fun jdkInfo(): UiJdkInfo = jdk.info().let { UiJdkInfo(it.home, it.version, it.srcZip) }

    /** Where Android packages install: the detected SDK, else a conventional per-OS location (created on use). */
    fun androidSdkRoot(): Path = AndroidSdk.findSdkRoot(workspaceRoot) ?: defaultAndroidSdkRoot()

    /** List the installable Android packages, each flagged [SdkPackage.installed]. Empty when offline. */
    suspend fun androidPackages(): List<UiSdkPackage> = withContext(Dispatchers.IO) {
        setBusy("Fetching the Android SDK package list…")
        try {
            val installed = runCatching { AndroidSdkInstaller.installedPackages(androidSdkRoot()) }.getOrDefault(emptySet())
            AndroidSdkInstaller.fetchPackages(fetcher).map {
                UiSdkPackage(it.path, it.displayName, it.category.name, it.sizeBytes, it.path in installed, it.archiveUrl != null)
            }
        } finally {
            idle()
        }
    }

    /** Install one Android package by its sdkmanager id (`platforms;android-34`, `sources;android-34`, …). */
    suspend fun installAndroidPackage(path: String): String = withContext(Dispatchers.IO) {
        val pkg = AndroidSdkInstaller.fetchPackages(fetcher).firstOrNull { it.path == path }
            ?: return@withContext "Package $path not found in the repository."
        val root = androidSdkRoot()
        runCatching { Files.createDirectories(root) }
        setBusy("Downloading ${pkg.displayName}…")
        try {
            val err = AndroidSdkInstaller.install(pkg, root, fetcher) { read, total -> progress("Installing ${pkg.displayName}", read, total) }
            if (err == null) { onChanged(); "Installed ${pkg.displayName}." } else err
        } finally {
            idle()
        }
    }

    /** Download Temurin JDK [feature] and keep its sources for the editor. */
    suspend fun downloadJdkSources(feature: Int): String = withContext(Dispatchers.IO) {
        setBusy("Downloading JDK $feature sources…")
        try {
            val err = jdk.downloadJdkSources(feature) { read, total -> progress("Downloading JDK $feature", read, total) }
            if (err == null) { onChanged(); "JDK $feature sources installed." } else err
        } finally {
            idle()
        }
    }

    private fun setBusy(message: String) { _state.value = UiSdkManagerState(busy = true, message = message, fraction = -1.0) }
    private fun idle() { _state.value = UiSdkManagerState() }
    private fun progress(label: String, read: Long, total: Long) {
        val frac = if (total > 0) read.toDouble() / total else -1.0
        _state.value = UiSdkManagerState(busy = true, message = "$label — ${mb(read)}${if (total > 0) " / ${mb(total)}" else ""}", fraction = frac)
    }

    private fun mb(bytes: Long): String = if (bytes <= 0) "" else "%.1f MB".format(bytes / 1_048_576.0)

    private fun defaultAndroidSdkRoot(): Path {
        val home = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> Path.of(home, "Library", "Android", "sdk")
            os.contains("win") -> Path.of(home, "AppData", "Local", "Android", "Sdk")
            else -> Path.of(home, "Android", "Sdk")
        }
    }
}
