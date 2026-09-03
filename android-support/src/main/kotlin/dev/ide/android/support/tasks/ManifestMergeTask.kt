package dev.ide.android.support.tasks

import dev.ide.android.support.manifest.ManifestMerger
import dev.ide.build.DiagnosticKind
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.reportToolDiagnostics
import java.nio.file.Files
import java.nio.file.Path

/**
 * `processManifest`/`process<Variant>Manifest`: merge the app manifest with every dependency-library and
 * AAR manifest ([ManifestMerger]) into one manifest fed to `aapt2 link`. Without this a library's
 * `<service>`/`<receiver>`/`<provider>`/`<meta-data>`/permission contributions are silently dropped — the
 * reason Firebase/Play Services need it. [libraryManifests] are in decreasing priority.
 */
internal class ManifestMergeTask(
    override val name: TaskName,
    private val primaryManifest: Path,
    private val libraryManifests: List<Path>,
    private val placeholders: Map<String, String>,
    private val minSdk: Int,
    private val targetSdk: Int,
    private val outManifest: Path,
    /** The module namespace, injected as the manifest `package` when it declares none (modern AGP style). */
    private val packageName: String,
    // When the build config's version is authoritative, drop the manifest's own android:versionCode/
    // versionName from the merged output so aapt2 injects the facet value instead (AGP's DSL-wins rule).
    private val stripVersionCode: Boolean = false,
    private val stripVersionName: Boolean = false,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("primary", listOf(primaryManifest).filter { Files.exists(it) })
            filePaths("libs", libraryManifests.filter { Files.exists(it) })
            // Placeholder values are part of the merged output, so a change must re-run the merge.
            property("placeholders", placeholders.toSortedMap().toString())
            // Not part of the output, but the library-minSdk gate compares against it: re-run so the error
            // appears/clears when the app's minSdk crosses a dependency's declared floor.
            property("minSdk", minSdk)
            // Not part of the output, but the edge-to-edge advisory depends on it: re-run so the warning
            // appears/clears when the resolved target crosses the threshold.
            property("targetSdk", targetSdk)
            // The package injected into the merged manifest (from the module namespace) is part of the output.
            property("packageName", packageName)
            // Whether the manifest's version is stripped changes the output, so bumping the facet version
            // from/to its default (which flips authority) must re-run the merge.
            property("stripVersionCode", stripVersionCode)
            property("stripVersionName", stripVersionName)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("manifest", outManifest) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (!Files.isRegularFile(primaryManifest))
            return TaskResult.Failed("app manifest not found: $primaryManifest")
        val libs = libraryManifests.filter { Files.isRegularFile(it) }
        // Catch Throwable (not just Exception): an XML/regex impl quirk on ART can surface as an Error
        // (e.g. ExceptionInInitializerError) — report it as a build failure with the cause, never crash.
        val result = try {
            ManifestMerger.merge(
                primaryManifest, libs, placeholders, appMinSdk = minSdk,
                stripVersionCode = stripVersionCode, stripVersionName = stripVersionName,
            )
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            return TaskResult.Failed("manifest merge crashed: ${cause::class.simpleName}: ${cause.message}", t)
        }

        val logs = result.messages.map { "${it.severity}: ${it.text}" }.toMutableList()
        edgeToEdgeAdvisory(targetSdk, result.xml)?.let { logs += "WARNING: $it" }
        logs.forEach(ctx.logger())
        ctx.reportToolDiagnostics("manifest-merger", logs, DiagnosticKind.GENERIC)
        if (result.hasErrors) return TaskResult.Failed("manifest merge failed (see diagnostics)")

        // Modern AGP apps declare `namespace` in Gradle and omit `package` from the manifest; AGP injects it
        // into the merged manifest before aapt2 (which still requires a `package` on `<manifest>`). Same here.
        val merged = ensurePackage(result.xml, packageName)
        outManifest.parent?.let { Files.createDirectories(it) }
        Files.write(outManifest, merged.toByteArray(Charsets.UTF_8))
        ctx.logger()("processManifest -> ${outManifest.fileName} (merged ${libs.size} library manifest(s))")
        return TaskResult.Success
    }

    companion object {
        /** Android 15 (VANILLA_ICE_CREAM): an app targeting this or higher gets edge-to-edge enforced by default. */
        const val EDGE_TO_EDGE_SDK = 35

        /**
         * Ensure the `<manifest>` opening tag carries a `package` attribute, injecting [packageName] (the module
         * namespace) when it has none. aapt2 still requires `package`, but AGP-style projects declare only
         * `namespace` in Gradle and omit it from the manifest — AGP injects it, and so must we.
         */
        fun ensurePackage(xml: String, packageName: String): String {
            if (packageName.isBlank()) return xml
            val start = xml.indexOf("<manifest")
            if (start < 0) return xml
            val tagEnd = xml.indexOf('>', start)
            if (tagEnd < 0) return xml
            val openTag = xml.substring(start, tagEnd)
            if (Regex("""\bpackage\s*=""").containsMatchIn(openTag)) return xml
            val insertAt = start + "<manifest".length
            return xml.substring(0, insertAt) + " package=\"$packageName\"" + xml.substring(insertAt)
        }

        /**
         * Heads-up returned when the app declares no `targetSdkVersion` (so the build config, via aapt2's
         * `--target-sdk-version`, is the effective target) and that target enforces edge-to-edge. This is the
         * surprising case: the value isn't visible in the manifest at all. Returns null when there is nothing
         * to flag. [mergedXml] is the linked manifest; after the merge a `targetSdkVersion` in it can only be
         * the app's own (a library's never reaches the output), so its absence means the app relies on the facet.
         */
        fun edgeToEdgeAdvisory(targetSdk: Int, mergedXml: String): String? =
            if (targetSdk >= EDGE_TO_EDGE_SDK && "targetSdkVersion" !in mergedXml)
                "targetSdk $targetSdk enables edge-to-edge by default on Android 15+ (content draws behind the " +
                    "status/navigation bars). Handle window insets (WindowCompat.setDecorFitsSystemWindows(window, " +
                    "false) plus an OnApplyWindowInsetsListener) or set a lower targetSdk."
            else null
    }
}
