package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives the native `aapt2` binary. `compile` turns each `res/` directory into a
 * flat-file archive; `link` merges those archives with the manifest against `android.jar` to emit
 * `resources.ap_` (a zip holding the binary manifest + `resources.arsc` + compiled res) and generates
 * `R.java`. `--auto-add-overlay` lets later source sets (build type / flavor) overlay the main set.
 */
class Aapt2Subprocess(private val aapt2: Path) : Aapt2 {

    override fun compile(resDirs: List<Path>, outDir: Path): Aapt2CompileResult {
        Files.createDirectories(outDir)
        val archives = ArrayList<Path>()
        val log = ArrayList<String>()
        resDirs.filter { Files.isDirectory(it) }.forEachIndexed { i, resDir ->
            val archive = outDir.resolve("res-$i.zip")
            val r = Subprocess.run(listOf(aapt2.toString(), "compile", "--dir", resDir.toString(), "-o", archive.toString()))
            log += r.log
            if (!r.success) return Aapt2CompileResult(archives, ToolResult(false, log + selfTest()))
            if (Files.exists(archive)) archives.add(archive)
        }
        return Aapt2CompileResult(archives, ToolResult.ok(log))
    }

    /**
     * Diagnose a failed aapt2 run: invoke `aapt2 version` (the cheapest possible command) and report what
     * happened. If even *that* dies by signal, the bundled binary cannot execute on this device at all —
     * an ABI/kernel/page-size mismatch, not an argument or resource problem — and the whole pipeline needs
     * a different aapt2 prebuilt. If it prints a version, the binary runs and the failure is in the inputs.
     */
    private fun selfTest(): List<String> {
        val r = Subprocess.run(listOf(aapt2.toString(), "version"))
        val banner = r.log.joinToString(" ").trim()
        return if (r.success)
            listOf("[diagnostic] `aapt2 version` works ($banner) — the binary runs; the failure is in the compile inputs/args.")
        else listOf(
            "[diagnostic] `aapt2 version` also failed: $banner",
            "[diagnostic] → the bundled aapt2 prebuilt cannot execute on this device (ABI/kernel/page-size mismatch); it needs replacing.",
        )
    }

    override fun link(
        compiled: List<Path>,
        manifest: Path,
        androidJar: Path,
        customPackage: String,
        extraPackages: List<String>,
        minSdk: Int,
        targetSdk: Int,
        genJavaDir: Path,
        outApk: Path,
        versionCode: Int?,
        versionName: String?,
        nonFinalIds: Boolean,
    ): ToolResult {
        Files.createDirectories(genJavaDir)
        outApk.parent?.let { Files.createDirectories(it) }
        val cmd = buildList {
            add(aapt2.toString()); add("link")
            add("-o"); add(outApk.toString())
            add("-I"); add(androidJar.toString())
            add("--manifest"); add(manifest.toString())
            add("--java"); add(genJavaDir.toString())
            add("--custom-package"); add(customPackage)
            if (extraPackages.isNotEmpty()) { add("--extra-packages"); add(extraPackages.joinToString(":")) }
            versionCode?.let { add("--version-code"); add(it.toString()) }
            versionName?.let { add("--version-name"); add(it) }
            if (nonFinalIds) add("--non-final-ids")
            add("--min-sdk-version"); add(minSdk.toString())
            add("--target-sdk-version"); add(targetSdk.toString())
            add("--auto-add-overlay")
            addAll(compiled.map { it.toString() })
        }
        val r = Subprocess.run(cmd)
        return if (r.success) r else ToolResult(false, r.log + selfTest())
    }
}
