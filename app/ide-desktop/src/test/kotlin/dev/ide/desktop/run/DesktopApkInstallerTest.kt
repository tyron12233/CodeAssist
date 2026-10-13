package dev.ide.desktop.run

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop android Run over adb: finding adb, reading devices, and the install-then-launch sequence. */
class DesktopApkInstallerTest {

    @Test
    fun parsesDevicesAndNamesThem() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
            R58M12ABCDE            unauthorized usb:1-1 transport_id:2
            192.168.1.5:5555       device product:panther model:Pixel_7 device:panther transport_id:3
        """.trimIndent()

        val devices = AdbDevice.parse(out)

        assertEquals(listOf("emulator-5554", "R58M12ABCDE", "192.168.1.5:5555"), devices.map { it.serial })
        assertEquals(listOf(true, false, true), devices.map { it.online })
        assertEquals("sdk gphone64 arm64 (emulator)", devices[0].label)
        assertEquals("R58M12ABCDE", devices[1].label, "no model yet: the serial")
        assertEquals("Pixel 7", devices[2].label)
    }

    @Test
    fun findsAdbInTheSdkThenOnPath() {
        if (!posix()) return
        val tmp = Files.createTempDirectory("adb-locate")
        try {
            val home = tmp.resolve("home").toString()
            assertNull(AdbLocator.find(env = { null }, home = home, os = "Mac OS X"))

            val onPath = executable(tmp.resolve("bin/adb"))
            assertEquals(onPath, AdbLocator.find(env = { if (it == "PATH") onPath.parent.toString() else null }, home = home, os = "Linux"))

            // The SDK's copy wins over PATH, including the default folder the SDK Manager installs into.
            val inSdk = executable(tmp.resolve("home/Library/Android/sdk/platform-tools/adb"))
            assertEquals(inSdk, AdbLocator.find(env = { if (it == "PATH") onPath.parent.toString() else null }, home = home, os = "Mac OS X"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun installsOnThePickedDeviceThenOpensItsLauncherActivity() {
        if (!posix()) return
        val tmp = Files.createTempDirectory("adb-run")
        try {
            val calls = tmp.resolve("calls.txt")
            val adb = fakeAdb(tmp, calls, installOutput = "Performing Streamed Install\nSuccess")
            val installer = DesktopApkInstaller(locate = { adb })
            val log = mutableListOf<String>()

            val ok = runBlocking { installer.installAndLaunch(tmp.resolve("app.apk"), "com.example.app", "192.168.1.5:5555") { log += it } }

            assertTrue(ok, log.joinToString("\n"))
            val argv = Files.readAllLines(calls)
            assertTrue(argv.any { it.startsWith("-s 192.168.1.5:5555 install -r -t ") }, argv.joinToString("\n"))
            assertTrue("-s 192.168.1.5:5555 shell am start -n com.example.app/.MainActivity" in argv, argv.joinToString("\n"))
            assertTrue(log.any { it.startsWith("Launched com.example.app on Pixel 7") }, log.joinToString("\n"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun saysWhatToDoWhenThereIsNoAdbOrNoDeviceOrTheInstallFails() {
        if (!posix()) return
        val tmp = Files.createTempDirectory("adb-fail")
        try {
            val noAdb = mutableListOf<String>()
            assertFalse(runBlocking { DesktopApkInstaller(locate = { null }).installAndLaunch(tmp.resolve("a.apk"), "p", null) { noAdb += it } })
            assertTrue(noAdb.any { "SDK Manager" in it }, noAdb.joinToString("\n"))

            val gone = mutableListOf<String>()
            val adb = fakeAdb(tmp, tmp.resolve("c.txt"), installOutput = "Success")
            assertFalse(runBlocking { DesktopApkInstaller(locate = { adb }).installAndLaunch(tmp.resolve("a.apk"), "p", "emulator-9999") { gone += it } })
            assertTrue(gone.any { "no longer connected" in it }, gone.joinToString("\n"))

            val refused = mutableListOf<String>()
            val failing = fakeAdb(tmp.resolve("f").also { Files.createDirectories(it) }, tmp.resolve("d.txt"),
                installOutput = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]", installExit = 1)
            assertFalse(runBlocking { DesktopApkInstaller(locate = { failing }).installAndLaunch(tmp.resolve("a.apk"), "com.x", null) { refused += it } })
            assertTrue(refused.any { "adb uninstall com.x" in it }, refused.joinToString("\n"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun posix() = Files.getFileStore(Files.createTempFile("p", "")).supportsFileAttributeView("posix")

    private fun executable(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    /** An `adb` that records each call's arguments to [calls] and answers like a real one with two devices. */
    private fun fakeAdb(dir: Path, calls: Path, installOutput: String, installExit: Int = 0): Path {
        val script = """
            #!/bin/sh
            echo "$*" >> "$calls"
            case "$*" in
              "devices -l") printf 'List of devices attached\nemulator-5554 device model:sdk_gphone64_arm64\n192.168.1.5:5555 device model:Pixel_7\n' ;;
              *" install "*) printf '%s\n' "$installOutput"; exit $installExit ;;
              *"resolve-activity"*) printf 'priority=0 preferredOrder=0 match=0x108000\ncom.example.app/.MainActivity\n' ;;
              *"am start"*) echo "Starting: Intent { cmp=com.example.app/.MainActivity }" ;;
            esac
        """.trimIndent() + "\n"
        return executable(dir.resolve("adb")).also { Files.writeString(it, script) }
    }
}
