package dev.ide.android.support.tools

import com.android.tools.build.bundletool.commands.BuildBundleCommand
import com.google.common.collect.ImmutableList
import java.nio.file.Files
import java.nio.file.Path

/**
 * Turns one or more *bundle module zips* (each: `manifest/AndroidManifest.xml` in proto, `dex/`, `res/`,
 * `resources.pb`, `assets/`, `lib/`, `root/`) into an Android App Bundle (`.aab`) via Google's `bundletool`
 * `build-bundle`. The single `base.zip` module is the whole app (we do not split dynamic features).
 *
 * bundletool is a pure-Java tool that is NOT part of the Android SDK build-tools (unlike d8/apksigner), so
 * there is no SDK path to a jar — the in-process implementation ([BundletoolInProcess]) is the primary one
 * and runs on both the desktop JVM and ART. A subprocess form ([BundletoolSubprocess]) is offered for a
 * host that ships the bundletool **all** (fat) jar and prefers to fork.
 */
data class BundleRequest(val moduleZips: List<Path>, val outputAab: Path)

interface Bundler {
    fun bundle(request: BundleRequest): ToolResult
}

/**
 * Builds the `.aab` in-process by calling `bundletool`'s [BuildBundleCommand] directly. bundletool (and its
 * guava/protobuf deps) is `compileOnly` in android-support and bundled by the hosts that produce bundles
 * (`:ide-android` for ART, `:ide-desktop`); a test supplies it via `testImplementation`.
 */
class BundletoolInProcess : Bundler {
    override fun bundle(request: BundleRequest): ToolResult {
        request.outputAab.parent?.let { Files.createDirectories(it) }
        // bundletool refuses to overwrite an existing output, so clear it first.
        runCatching { Files.deleteIfExists(request.outputAab) }
        val present = request.moduleZips.filter { Files.exists(it) }
        if (present.isEmpty()) return ToolResult.fail("no bundle module zips to build")
        return try {
            BuildBundleCommand.builder()
                .setModulesPaths(ImmutableList.copyOf(present))
                .setOutputPath(request.outputAab)
                .build()
                .execute()
            ToolResult.ok(listOf("bundletool (in-process) built ${request.outputAab.fileName}"))
        } catch (t: Throwable) {
            // Report the FULL cause chain + a compact stack, not just `t.message` (often null/opaque). bundletool
            // runs in-process on the same VM, so on ART a failure is frequently a platform gap — e.g. it reaches
            // for a JAXP/SAX class the JDK has but ART does not; the class name only shows up down the chain.
            ToolResult.fail("bundletool build-bundle failed (${t.javaClass.simpleName})", diagnose(t))
        }
    }

    private companion object {
        /** Flatten [t]'s message + cause chain + the top stack frames into console lines. */
        fun diagnose(t: Throwable): List<String> {
            val lines = ArrayList<String>()
            lines += "bundletool build-bundle failed in-process on ${System.getProperty("java.vm.name")} " +
                "${System.getProperty("java.vm.version")}"
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < 10) {
                lines += (if (depth == 0) "  " else "  caused by: ") + "${cur.javaClass.name}: ${cur.message}"
                cur.stackTrace.take(8).forEach { lines += "      at $it" }
                cur = cur.cause
                depth++
            }
            return lines
        }
    }
}

/**
 * Builds the `.aab` by forking `java -jar bundletool-all.jar build-bundle …`. For a desktop host that has
 * downloaded the bundletool fat jar (the GitHub release asset, not the Maven artifact, which lacks the deps
 * to run standalone). Not wired by default — [BundletoolInProcess] is the default everywhere.
 */
class BundletoolSubprocess(private val bundletoolJar: Path, private val javaLauncher: Path) : Bundler {
    override fun bundle(request: BundleRequest): ToolResult {
        request.outputAab.parent?.let { Files.createDirectories(it) }
        runCatching { Files.deleteIfExists(request.outputAab) }
        val present = request.moduleZips.filter { Files.exists(it) }
        if (present.isEmpty()) return ToolResult.fail("no bundle module zips to build")
        val cmd = listOf(
            javaLauncher.toString(), "-jar", bundletoolJar.toString(), "build-bundle",
            "--modules=${present.joinToString(",")}",
            "--output=${request.outputAab}",
            "--overwrite",
        )
        return Subprocess.run(cmd)
    }
}
