package dev.ide.desktop.run

import dev.ide.core.ApkInstaller
import dev.ide.core.InstallTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The android Run on desktop: install the built APK on a phone or emulator over adb, then open it.
 *
 * Run lists one entry per connected device ([targets]), read from a list a background thread keeps fresh, since
 * the Run menu asks on the UI thread. The poll only starts once something asks (an Android project's Run menu),
 * so a desktop session that never touches Android never starts an adb server.
 */
internal class DesktopApkInstaller(
    private val locate: () -> Path? = { AdbLocator.find() },
    private val pollMillis: Long = 2_000,
) : ApkInstaller {

    @Volatile private var known: List<AdbDevice> = emptyList()
    private var poller: Thread? = null

    override val choosesTarget: Boolean get() = true

    override fun targets(): List<InstallTarget> {
        startPolling()
        return known.filter { it.online }.map { InstallTarget(it.serial, it.label) }
    }

    @Synchronized
    private fun startPolling() {
        if (poller != null) return
        poller = Thread({
            while (true) {
                known = locate()?.let { runCatching { Adb(it).devices() }.getOrNull() }.orEmpty()
                Thread.sleep(pollMillis)
            }
        }, "adb-devices").apply { isDaemon = true; start() }
    }

    override suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean =
        installAndLaunch(apk, packageName, null, log)

    override suspend fun installAndLaunch(
        apk: Path,
        packageName: String,
        target: String?,
        log: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val adbPath = locate()
        if (adbPath == null) {
            log("adb was not found, so the app was built but not installed. The APK is at:")
            log("  $apk")
            log("Install Platform tools from Settings > SDK Manager, or set ANDROID_HOME to an Android SDK.")
            return@withContext false
        }
        val adb = Adb(adbPath)
        val devices = adb.devices().also { known = it }
        val device = pickDevice(devices, target, log) ?: return@withContext false

        if (!install(adb, device, apk, packageName, log)) return@withContext false
        launch(adb, device, packageName, log)
        true
    }

    override val runsInstrumentation: Boolean get() = true

    override suspend fun runInstrumentation(
        appApk: Path,
        appPackage: String,
        testApk: Path,
        testPackage: String,
        runner: String,
        args: List<Pair<String, String>>,
        target: String?,
        log: (String) -> Unit,
        onLine: (String) -> Unit,
    ): Boolean = runInterruptible(Dispatchers.IO) {
        val adbPath = locate()
        if (adbPath == null) {
            log("adb was not found, so the tests were built but not run.")
            log("Install Platform tools from Settings > SDK Manager, or set ANDROID_HOME to an Android SDK.")
            return@runInterruptible false
        }
        val adb = Adb(adbPath)
        val devices = adb.devices().also { known = it }
        val device = pickDevice(devices, target, log) ?: return@runInterruptible false
        if (!install(adb, device, appApk, appPackage, log)) return@runInterruptible false
        if (!install(adb, device, testApk, testPackage, log)) return@runInterruptible false
        log("Running instrumented tests on ${device.label}…")
        val command = listOf("-s", device.serial, "shell", "am", "instrument", "-r", "-w") +
            args.flatMap { (k, v) -> listOf("-e", k, v) } + "$testPackage/$runner"
        // As long as the tests take: a stuck test is stopped by the user, which interrupts this call.
        val run = adb.run(*command.toTypedArray(), timeoutSeconds = 6 * 60 * 60, onLine = onLine)
        if (!run.ok) log("am instrument ended with exit code ${run.exitCode}.")
        true
    }

    /** `adb install -r -t` [apk] on [device]; false after saying why it failed. */
    private fun install(adb: Adb, device: AdbDevice, apk: Path, packageName: String, log: (String) -> Unit): Boolean {
        log("Installing ${apk.fileName} on ${device.label} (${device.serial})…")
        val install = adb.run("-s", device.serial, "install", "-r", "-t", apk.toString(), timeoutSeconds = 300) { line ->
            if (line.isNotBlank() && !line.startsWith("Performing")) log("  $line")
        }
        if (!install.ok || !install.output.contains("Success")) {
            log("Install failed.")
            installHint(install.output, packageName)?.let(log)
            return false
        }
        return true
    }

    /** The device to install on, or null after saying why there is none. */
    private fun pickDevice(devices: List<AdbDevice>, target: String?, log: (String) -> Unit): AdbDevice? {
        val online = devices.filter { it.online }
        if (target != null) {
            online.firstOrNull { it.serial == target }?.let { return it }
            log("$target is no longer connected. Pick a device from the Run menu again.")
            return null
        }
        if (online.isEmpty()) {
            val waiting = devices.filter { it.state == "unauthorized" }
            if (waiting.isNotEmpty()) {
                log("${waiting.joinToString { it.label }}: allow USB debugging in the prompt on the phone, then run again.")
            } else {
                log("No device is connected. Start an emulator, or connect a phone with USB debugging turned on")
                log("(Settings > Developer options), then run again.")
            }
            return null
        }
        if (online.size > 1) log("Several devices are connected; using ${online[0].label}. Pick another from the Run menu.")
        return online[0]
    }

    /** Open the app's launcher activity. An app without one is installed and left closed, which is not a failure. */
    private fun launch(adb: Adb, device: AdbDevice, packageName: String, log: (String) -> Unit) {
        val resolved = adb.run(
            "-s", device.serial, "shell", "cmd", "package", "resolve-activity", "--brief",
            "-c", "android.intent.category.LAUNCHER", packageName,
        )
        val component = resolved.output.lineSequence().map { it.trim() }.lastOrNull { '/' in it && it.startsWith(packageName) }
        val started = if (component != null) {
            adb.run("-s", device.serial, "shell", "am", "start", "-n", component)
        } else {
            // Older Android has no `cmd package resolve-activity`: monkey opens the launcher activity by package.
            adb.run("-s", device.serial, "shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
        }
        when {
            started.output.contains("No activities found") ->
                log("Installed. $packageName has no launcher activity, so nothing was opened.")
            started.ok && !started.output.contains("Error") -> log("Launched $packageName on ${device.label}.")
            else -> log("Installed, but it could not be opened: ${started.output.trim().lineSequence().lastOrNull().orEmpty()}")
        }
    }

    companion object {
        /** A next step for the install failures people actually hit, or null when there is nothing to add. */
        internal fun installHint(output: String, packageName: String): String? = when {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output ->
                "A copy signed with a different key is already installed. Uninstall it first: adb uninstall $packageName"
            "INSTALL_FAILED_VERSION_DOWNGRADE" in output ->
                "A newer version is already installed. Raise versionCode, or uninstall the installed app first."
            "INSTALL_FAILED_OLDER_SDK" in output ->
                "The device runs an older Android than the app's minSdk."
            "INSTALL_FAILED_USER_RESTRICTED" in output ->
                "The phone refused the install. Allow it on the phone (on some phones: Developer options > Install via USB)."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in output -> "The device is out of storage."
            "INSTALL_FAILED_NO_MATCHING_ABIS" in output ->
                "The app's native code does not include this device's CPU architecture."
            else -> output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }
    }
}
