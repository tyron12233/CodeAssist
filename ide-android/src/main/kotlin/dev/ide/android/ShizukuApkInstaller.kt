package dev.ide.android

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume

/** Installs an APK through Shizuku's privileged shell, if Shizuku is running and permission is granted. */
internal object ShizukuApkInstaller {
    enum class Result { SUCCESS, UNAVAILABLE, FAILED }

    private const val PERMISSION_REQUEST = 0xCA71
    private val installMutex = Mutex()

    /**
     * Streams the APK to `pm install` over stdin, so the Shizuku shell never needs filesystem access to the
     * app's private project directory. [Result.UNAVAILABLE] and [Result.FAILED] are intentionally distinct for
     * logging, but both tell the caller to continue with Android's regular PackageInstaller.
     */
    suspend fun install(context: Context, apk: Path, log: (String) -> Unit): Result = installMutex.withLock {
        if (!Files.isRegularFile(apk)) return@withLock Result.FAILED
        if (!awaitBinder(context) || runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            return@withLock Result.UNAVAILABLE
        }
        if (!awaitPermission()) return@withLock Result.UNAVAILABLE

        log("Installing ${apk.fileName} with Shizuku…")
        runCatching {
            val size = Files.size(apk)
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(
                arrayOf("/system/bin/pm", "install", "-r", "-S", size.toString()),
                null,
                null,
            )

            Files.newInputStream(apk).use { input ->
                FileOutputStream(process.outputStream.fileDescriptor).use { output -> input.copyTo(output) }
            }
            val stdout = FileInputStream(process.inputStream.fileDescriptor).bufferedReader().use { it.readText() }.trim()
            val stderr = FileInputStream(process.errorStream.fileDescriptor).bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            val message = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

            if (exitCode == 0 && stdout.lineSequence().any { it.trim() == "Success" }) {
                Result.SUCCESS
            } else {
                log("Shizuku install failed${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}")
                Result.FAILED
            }
        }.getOrElse {
            log("Shizuku install failed: ${it.message ?: it.javaClass.simpleName}")
            Result.FAILED
        }
    }

    /** The provider lives in the UI process; this requests its binder when called from the :build process. */
    private suspend fun awaitBinder(context: Context): Boolean {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
        runCatching { ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext) }
        return withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine { continuation ->
                lateinit var listener: Shizuku.OnBinderReceivedListener
                listener = Shizuku.OnBinderReceivedListener {
                    Shizuku.removeBinderReceivedListener(listener)
                    if (continuation.isActive) continuation.resume(true)
                }
                Shizuku.addBinderReceivedListenerSticky(listener)
                continuation.invokeOnCancellation { Shizuku.removeBinderReceivedListener(listener) }
            }
        } ?: false
    }

    private suspend fun awaitPermission(): Boolean {
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)) return false

        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { continuation ->
                lateinit var listener: Shizuku.OnRequestPermissionResultListener
                listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode == PERMISSION_REQUEST && continuation.isActive) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
                    .onFailure {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        } ?: false
    }
}
