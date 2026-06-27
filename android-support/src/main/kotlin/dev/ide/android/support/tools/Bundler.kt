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
            ToolResult.fail("bundletool build-bundle failed: ${t.message}")
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
