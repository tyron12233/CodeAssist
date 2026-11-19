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
        // A host with aapt2 linked into it has no binary to ship, only the platform jar.
        if (dev.ide.android.support.tools.Aapt2InProcess.runner != null && Files.isRegularFile(dir.resolve("android.jar"))) {
            return AndroidSdk(androidJar = dir.resolve("android.jar"), buildToolsDir = dir, aapt2 = shipped)
        }
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

    /**
     * The debug keystore shipped beside the toolchain, for a host that cannot run `keytool` to make one (the
     * same shared, non-secret key the Android app carries as an asset).
     */
    fun debugKeystore(): Path? =
        System.getProperty(DIR_PROPERTY)?.let { Paths.get(it).resolve("debug.keystore") }?.takeIf { Files.isRegularFile(it) }

    /**
     * The platform library to compile against where the JVM this runs in has none a compiler can read: a JDK
     * with no `lib/modules` image (the one an iOS app embeds runs from unpacked class folders), which ecj
     * rejects as a system library. The toolchain's `android.jar` and the lambda stubs beside it then stand in,
     * as they do on a phone. Empty on an ordinary JDK, where the compilers read the host's own.
     */
    fun bootClasspathWithoutHostJdk(): List<Path> {
        val home = System.getProperty("java.home") ?: return emptyList()
        if (Files.exists(Paths.get(home, "lib", "modules")) || Files.isDirectory(Paths.get(home, "jmods"))) return emptyList()
        val dir = System.getProperty(DIR_PROPERTY)?.let { Paths.get(it) } ?: return emptyList()
        return listOf(dir.resolve("android.jar"), dir.resolve("core-lambda-stubs.jar")).filter { Files.isRegularFile(it) }
    }

    /** The `android.jar` to compile and preview against: an installed SDK's platform, else the bundled one. */
    fun androidJar(): Path? =
        AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.androidJar ?: bundled()?.androidJar
            // A host that cannot run aapt2 at all (an iOS app's embedded JVM) still compiles against the jar.
            ?: System.getProperty(DIR_PROPERTY)?.let { Paths.get(it).resolve("android.jar") }?.takeIf { Files.isRegularFile(it) }
}
