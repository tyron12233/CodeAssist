package dev.ide.android.support.tasks

import dev.ide.android.support.AndroidPackaging
import dev.ide.android.support.tools.ArtProfiles
import dev.ide.android.support.tools.BundleMetadataFile
import dev.ide.android.support.tools.BundleRequest
import dev.ide.android.support.tools.BundleSigner
import dev.ide.android.support.tools.Bundler
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.DiagnosticKind
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import java.nio.file.Files
import java.nio.file.Path

/**
 * `package<Variant>Bundle`: assemble the bundletool base module zip ([BundlePackaging]) from the proto
 * resources + dex + assets + native libs, then run `bundletool build-bundle` to produce the unsigned `.aab`.
 * The module's no-compress suffixes go into the bundle config as `uncompressedGlob`s, which is how the APKs
 * Play generates from the bundle keep those entries STORED (the module zip's own compression is irrelevant).
 */
internal class BundleTask(
    override val name: TaskName,
    private val protoAp: Path,
    private val dexDirs: List<Path>,
    private val assetsDirs: List<Path>,
    private val jniLibDirs: List<Path>,
    private val outAab: Path,
    private val baseModuleZip: Path,
    private val bundler: Bundler,
    private val javaResJars: List<Path> = emptyList(),
    /** The compiled Baseline Profile (`.prof`, `.profm`) from [CompileArtProfileTask], added as bundle metadata. */
    private val artProfile: Pair<Path, Path>? = null,
    private val packaging: AndroidPackaging = AndroidPackaging(),
) : Task {
    private val uncompressedGlobs: List<String> get() = AndroidPackaging.bundleGlobs(packaging.noCompressSuffixes)

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("ap", listOf(protoAp).filter { Files.exists(it) })
            dirPaths("dex", dexDirs)
            dirPaths("assets", assetsDirs)
            dirPaths("jni", jniLibDirs)
            filePaths("javaRes", javaResJars)
            artProfile?.let { (prof, profm) -> filePaths("artProfile", listOf(prof, profm).filter { Files.exists(it) }) }
            property("uncompressedGlobs", uncompressedGlobs.joinToString("\n"))
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("aab", outAab) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (!Files.isRegularFile(protoAp))
            return TaskResult.Failed("proto resources missing (aapt2 --proto-format link did not run): $protoAp")
        return runCatching {
            val entries = BundlePackaging.buildBaseModuleZip(protoAp, dexDirs, assetsDirs, jniLibDirs, baseModuleZip, javaResJars)
            ctx.debug("bundle module -> base.zip (${entries.size} entries)")
            val metadata = artProfile?.let { (prof, profm) ->
                listOf(ArtProfiles.PROFILE_NAME to prof, ArtProfiles.PROFILE_METADATA_NAME to profm)
                    .filter { Files.isRegularFile(it.second) }
                    .map { (fileName, file) -> BundleMetadataFile(ArtProfiles.BUNDLE_METADATA_DIR, fileName, file) }
            }.orEmpty()
            val r = bundler.bundle(BundleRequest(listOf(baseModuleZip), outAab, metadata, uncompressedGlobs))
            ctx.reportTool("bundletool", r, DiagnosticKind.PACKAGING)
            if (r.success) TaskResult.Success as TaskResult
            else TaskResult.Failed("bundletool build-bundle failed")
        }.getOrElse { TaskResult.Failed("bundle failed: ${it.message}", it) }
    }
}

/** `sign<Variant>Bundle`: JAR (v1) sign the `.aab` with the project keystore. */
internal class SignBundleTask(
    override val name: TaskName,
    private val unsignedAab: Path,
    private val signedAab: Path,
    private val config: SigningConfig,
    private val minApi: Int,
    private val signer: BundleSigner,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("unsigned", listOf(unsignedAab).filter { Files.exists(it) })
            property("keystore", config.keystore.toString())
            property("alias", config.keyAlias)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("aab", signedAab) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (!Files.isRegularFile(unsignedAab)) return TaskResult.Failed("unsigned .aab missing: $unsignedAab")
        val r = signer.sign(unsignedAab, signedAab, config, minApi)
        ctx.reportTool("bundle-signer", r, DiagnosticKind.PACKAGING)
        return if (r.success) TaskResult.Success else TaskResult.Failed("bundle signing failed")
    }
}
