package dev.ide.core.sdk

import dev.ide.android.support.tools.AndroidSdk
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The Android toolchain a desktop install ships inside the app, so an Android build works on a machine with no
 * Android SDK: the platform `android.jar`, the desugar stubs (`core-lambda-stubs.jar`) and a host `aapt2`. D8 and
 * apksigner need nothing on disk, since with this toolchain the build runs them in-process, as on device.
 *
 * The desktop launcher points [DIR_PROPERTY] at the staged toolchain dir. Unset (tests, the build CLI, the device)
 * means there is none, and only an installed SDK can build.
 */
object BundledAndroidToolchain {
    const val DIR_PROPERTY = "codeassist.android.toolchain"

    private val IS_WINDOWS = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The installed Android SDK when it has everything the subprocess pipeline runs, else null. */
    fun installed(): AndroidSdk? =
        AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.takeIf { it.isComplete() }

    /** The toolchain inside the app, or null when the launcher staged none. */
    fun bundled(): AndroidSdk? {
        val dir = System.getProperty(DIR_PROPERTY)?.let { Paths.get(it) } ?: return null
        val shipped = dir.resolve(if (IS_WINDOWS) "aapt2.exe" else "aapt2")
        if (!Files.isRegularFile(shipped) || !Files.isRegularFile(dir.resolve("android.jar"))) return null
        val aapt2 = runnable(shipped) ?: return null
        return AndroidSdk(androidJar = dir.resolve("android.jar"), buildToolsDir = dir, aapt2 = aapt2)
    }

    /**
     * [aapt2] itself when it can be executed, else an executable copy outside the app. jpackage drops the exec bit
     * on app resources, and a signed or read-only bundle must not be changed, so the copy lives in the user's
     * `~/.codeassist/toolchain`, keyed by size so an update brings its own.
     */
    private fun runnable(aapt2: Path): Path? {
        if (Files.isExecutable(aapt2)) return aapt2
        return runCatching {
            val size = Files.size(aapt2)
            val copy = Paths.get(System.getProperty("user.home"), ".codeassist", "toolchain", "aapt2-$size")
                .resolve(aapt2.fileName.toString())
            if (!Files.isExecutable(copy) || Files.size(copy) != size) {
                Files.createDirectories(copy.parent)
                val tmp = Files.createTempFile(copy.parent, "aapt2", ".tmp")
                Files.copy(aapt2, tmp, StandardCopyOption.REPLACE_EXISTING)
                tmp.toFile().setExecutable(true)
                Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            copy
        }.getOrNull()
    }

    /** The `android.jar` to compile and preview against: an installed SDK's platform, else the bundled one. */
    fun androidJar(): Path? =
        AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.androidJar ?: bundled()?.androidJar
}
