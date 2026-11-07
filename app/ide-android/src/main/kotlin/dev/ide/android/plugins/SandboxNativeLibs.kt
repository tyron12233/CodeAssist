package dev.ide.android.plugins

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import dev.ide.android.AndroidIde
import dev.ide.android.support.tools.ApksigSigner
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.SigningConfig
import dev.ide.core.plugins.PluginExecutables
import dev.ide.platform.log.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * An installed home for the native programs of the plugin under test in the sandbox.
 *
 * A plugin runs its packaged toolchain (`lib/<abi>/lib<name>.so`) from [dev.ide.plugin.PluginRegistration
 * .nativeLibraryDir], which for an installed plugin is the directory the package installer extracted it to. The
 * sandbox loads a build that was never installed, and since Android 10 nothing in the app's own storage may be
 * `exec`ed: unpacked there, every program the plugin starts failed with `error=13, Permission denied`, while
 * the same build installed worked (reported with a C# plugin's compiler; the NDK and Dart plugins are the same).
 *
 * So when a build packages a program ([PluginExecutables.scan]), the real IDE puts that build's `lib/` into a
 * small app of its own, [carrierPackage], and installs it ([ensureInstalled]); the sandbox then hands the
 * plugin that package's library directory ([installedDir]). One fixed package, not the plugin's: installing
 * the plugin itself would replace the copy the developer already has installed, and the real IDE would load
 * it. Android asks to install the first time; later updates of an app this IDE installed go through without
 * asking on Android 12+. A build whose libraries did not change is not installed again, and one that packages
 * only JNI libraries (mapped, never run, which the app's storage still allows) never is.
 */
internal object SandboxNativeLibs {
    private val log = Log.logger("PluginSandbox")

    fun carrierPackage(context: Context): String = context.packageName + ".sandboxlibs"

    /** The fingerprint of the libraries [carrierPackage] holds, written once their install succeeded. */
    private fun installedId(context: Context) = File(SandboxProcess.stateDir(context), "native-libs.id")

    /** Before Android 10 a program runs from the app's storage, so no install is needed. */
    private val needed: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun scan(apk: File): PluginExecutables.Scan? =
        runCatching { PluginExecutables.scan(apk.toPath(), Build.SUPPORTED_ABIS.toList()) }
            .onFailure { log.warn("could not read the native libraries of $apk", it) }.getOrNull()

    /** In the sandbox: the installed directory holding exactly [apk]'s libraries, or null when there is none. */
    fun installedDir(context: Context, apk: File): Path? {
        if (!needed) return null
        val scan = scan(apk) ?: return null
        if (runCatching { installedId(context).readText() }.getOrNull() != scan.fingerprint) return null
        return carrierLibDir(context)
    }

    private fun carrierLibDir(context: Context): Path? = runCatching {
        context.packageManager.getApplicationInfo(carrierPackage(context), 0).nativeLibraryDir
    }.getOrNull()?.let { Paths.get(it) }?.takeIf { Files.isDirectory(it) }

    /**
     * In the real IDE, before the sandbox loads [apk]: make sure [carrierPackage] holds its libraries. Blocks
     * until the install finished (including the user's answer to Android's prompt). Returns false, having said
     * why, when the build packages programs that will not be able to run.
     */
    fun ensureInstalled(context: Context, apk: File, say: (String) -> Unit): Boolean {
        if (!needed) return true
        val scan = scan(apk) ?: return true
        if (runCatching { installedId(context).readText() }.getOrNull() == scan.fingerprint && carrierLibDir(context) != null) {
            return true
        }
        val names = scan.executables.joinToString { it.substringAfterLast('/') }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            say("The plugin packages programs ($names). Android runs those only from an installed app, so the sandbox")
            say("installs them as \"${carrierPackage(context)}\". Allow CodeAssist to install apps (Settings → Install")
            say("unknown apps), then Run again.")
            return false
        }
        say("Installing the plugin's programs ($names) for the sandbox: Android runs them only from an installed app.")
        val work = File(SandboxProcess.stateDir(context), "native-libs-build").apply { deleteRecursively(); mkdirs() }
        return try {
            val signed = build(context, apk, scan, work)
            when (val outcome = install(context, signed)) {
                null -> {
                    installedId(context).writeText(scan.fingerprint)
                    say("Installed.")
                    true
                }
                else -> {
                    say("Could not install the plugin's programs ($outcome); running them in the sandbox will fail.")
                    false
                }
            }
        } catch (t: Throwable) {
            log.warn("could not install the sandbox's native libraries", t)
            say("Could not prepare the plugin's programs (${t.message ?: t}); running them in the sandbox will fail.")
            false
        } finally {
            work.deleteRecursively()
        }
    }

    /** The carrier APK: a manifest-only app (linked by the bundled aapt2) plus [scan]'s libraries, signed. */
    private fun build(context: Context, apk: File, scan: PluginExecutables.Scan, work: File): Path {
        val manifest = File(work, "AndroidManifest.xml").apply {
            writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${carrierPackage(context)}">
                    <application android:label="CodeAssist plugin sandbox programs" android:hasCode="false"
                        android:extractNativeLibs="true" android:allowBackup="false" />
                </manifest>
                """.trimIndent(),
            )
        }
        val home = AndroidIde.appHomeDir(context)
        val aapt2 = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        val linked = File(work, "linked.apk")
        val process = ProcessBuilder(
            aapt2.absolutePath, "link",
            "-o", linked.absolutePath,
            "-I", provisioned(context, home, "android.jar", work).absolutePath,
            "--manifest", manifest.absolutePath,
            // Constant, like everything else in the carrier, so the same libraries make the same APK byte for
            // byte: Play Protect asks to scan every APK it has not seen, and a retry should not count as new.
            // An update to an equal version is allowed.
            "--version-code", "1",
            "--version-name", "1",
            "--min-sdk-version", Build.VERSION_CODES.Q.toString(),
            // Android 14 refuses to install (and to update without asking) an app that targets an old SDK.
            "--target-sdk-version", context.applicationInfo.targetSdkVersion.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0 && linked.isFile) { "aapt2 link failed: ${output.trim()}" }

        val unsigned = File(work, "unsigned.apk")
        ZipOutputStream(unsigned.outputStream().buffered()).use { out ->
            ZipFile(linked).use { zip -> zip.entries().asSequence().forEach { e -> copy(zip, e, e.name, out) } }
            ZipFile(apk).use { zip ->
                for (name in scan.entries) copy(zip, zip.getEntry(name) ?: continue, name, out)
            }
        }
        val signed = File(work, "signed.apk").toPath()
        val keystore = provisioned(context, home, "debug.keystore", work).toPath()
        val result = ApksigSigner().sign(
            unsigned.toPath(), signed,
            SigningConfig(keystore, DebugKeystore.STORE_PASS, DebugKeystore.KEY_ALIAS, DebugKeystore.KEY_PASS),
        )
        check(result.success) { result.log.joinToString(" ") }
        return signed
    }

    /** The IDE's copy of the bundled asset [name] in [home], or a fresh copy in [work] when there is none yet. */
    private fun provisioned(context: Context, home: File, name: String, work: File): File =
        File(home, name).takeIf { it.isFile && it.length() > 0 }
            ?: File(work, name).also { out -> context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } } }

    /** Copies [entry] keeping how it was stored: `resources.arsc` must stay uncompressed for Android 11+. */
    private fun copy(zip: ZipFile, entry: ZipEntry, name: String, out: ZipOutputStream) {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val copy = ZipEntry(name)
        copy.time = FIXED_ENTRY_TIME // reproducible bytes (see the version code in [build])
        if (entry.method == ZipEntry.STORED) {
            copy.method = ZipEntry.STORED
            copy.size = bytes.size.toLong()
            copy.compressedSize = bytes.size.toLong()
            copy.crc = CRC32().apply { update(bytes) }.value
        }
        out.putNextEntry(copy)
        out.write(bytes)
        out.closeEntry()
    }

    /** Installs [signed] as [carrierPackage] and waits for the outcome: null on success, else what went wrong. */
    private fun install(context: Context, signed: Path): String? {
        val pm = context.packageManager
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(carrierPackage(context))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val started = System.currentTimeMillis()
        val sessionId = installer.createSession(params)
        val done = CountDownLatch(1)
        val outcome = AtomicReference<String?>("did not finish")
        val action = "$INSTALL_ACTION.$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        runCatching { context.startActivity(confirm!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { outcome.set("Android's install prompt could not be shown"); done.countDown() }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { outcome.set(null); done.countDown() }
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        outcome.set(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else message ?: "status $status")
                        done.countDown()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, Files.size(signed)).use { out ->
                    Files.newInputStream(signed).use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, Intent(action).setPackage(context.packageName), flags)
                session.commit(pending.intentSender)
            }
            return awaitOutcome(context, sessionId, started, done, outcome)
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            return t.message ?: t.toString()
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Waits for the install's outcome. The status broadcast is not enough on its own: Android 14+ holds a
     * broadcast to a process it counts as cached, and the IDE's can be one behind the installer's dialog (seen on
     * an API 37 emulator: the update was installed, the success never arrived, and the finished session was still
     * reported). So the carrier itself is watched too: updated since this install began is the success. A
     * session given up on is abandoned, so its prompt cannot outlive this run.
     */
    private fun awaitOutcome(
        context: Context,
        sessionId: Int,
        started: Long,
        done: CountDownLatch,
        outcome: AtomicReference<String?>,
    ): String? {
        val deadline = started + TimeUnit.MINUTES.toMillis(INSTALL_WAIT_MIN)
        while (System.currentTimeMillis() < deadline) {
            if (done.await(POLL_MS, TimeUnit.MILLISECONDS)) return outcome.get()
            val updated = runCatching {
                context.packageManager.getPackageInfo(carrierPackage(context), 0).lastUpdateTime
            }.getOrNull()
            // Both times are the device's wall clock (this process and the package manager's).
            if (updated != null && updated >= started) return null
        }
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        return "no answer to Android's install prompt"
    }

    private const val POLL_MS = 500L
    private const val FIXED_ENTRY_TIME = 1_262_304_000_000L // 2010-01-01: any fixed time inside the zip date range
    private const val INSTALL_ACTION = "dev.ide.android.SANDBOX_LIBS_INSTALL"
    private const val INSTALL_WAIT_MIN = 5L
}
