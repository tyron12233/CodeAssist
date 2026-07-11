package dev.ide.android.support.tasks

import dev.ide.android.support.tools.AarMetadata
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.Task
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText

/**
 * Pure-`java.util.zip` assembly of an Android library archive (`.aar`) — mirrors [ApkPackaging]'s style
 * (no external zip tool). The AAR is the interchange format an `android-lib` publishes for consumers:
 * a `classes.jar` of the library's code (Java + Kotlin, WITHOUT the compile-only R), the library
 * `AndroidManifest.xml`, its RAW `res/` and `assets/`, the R symbol table `R.txt`, native libs under
 * `jni/<abi>/`, an optional `proguard.txt` of consumer keep rules, and the `aar-metadata.properties`
 * AGP consumers read. The layout matches what [dev.ide.android.support.tools.AarExtractor] consumes.
 */
internal object AarPackaging {

    /** An empty (zero-entry) zip — a valid `classes.jar` when the library compiled no classes. */
    private val EMPTY_ZIP = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    fun assembleAar(
        classesJar: Path,
        manifest: Path,
        packageName: String,
        resDirs: List<Path>,
        rTxt: Path,
        assetsDirs: List<Path>,
        jniLibDirs: List<Path>,
        proguardText: String,
        compileSdk: Int,
        outAar: Path,
    ): List<String> {
        outAar.parent?.let { Files.createDirectories(it) }
        val written = LinkedHashSet<String>()
        ZipOutputStream(Files.newOutputStream(outAar)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)

            // classes.jar — the library's own code (an AAR always carries one, even if empty).
            if (Files.isRegularFile(classesJar)) putFile(zos, "classes.jar", classesJar, written)
            else putBytes(zos, "classes.jar", EMPTY_ZIP, written)

            // AndroidManifest.xml — the library manifest (synthesize a minimal one if the module has none).
            if (Files.isRegularFile(manifest)) putFile(zos, "AndroidManifest.xml", manifest, written)
            else putBytes(zos, "AndroidManifest.xml", synthManifest(packageName), written)

            // res/ (raw, uncompiled) — first writer of a given relative path wins (main before variant overlays).
            resDirs.forEach { putTree(zos, "res", it, written) }
            // R.txt — the symbol table (empty when aapt2 produced none).
            if (Files.isRegularFile(rTxt)) putFile(zos, "R.txt", rTxt, written) else putBytes(zos, "R.txt", ByteArray(0), written)
            // assets/
            assetsDirs.forEach { putTree(zos, "assets", it, written) }
            // jni/<abi>/…  (jniLibs are already laid out <abi>/lib*.so)
            jniLibDirs.forEach { putTree(zos, "jni", it, written) }
            // proguard.txt — consumer keep rules applied by the app's R8 (omitted when there are none).
            if (proguardText.isNotBlank()) putBytes(zos, "proguard.txt", proguardText.toByteArray(Charsets.UTF_8), written)
            // The AGP metadata consumers read (minCompileSdk gate + format versions).
            putBytes(zos, AarMetadata.ENTRY_PATH, metadataProperties(compileSdk), written)
        }
        return written.toList()
    }

    private fun putFile(zos: ZipOutputStream, entryName: String, file: Path, written: MutableSet<String>) {
        if (!written.add(entryName)) return
        zos.putNextEntry(ZipEntry(entryName))
        Files.copy(file, zos)
        zos.closeEntry()
    }

    private fun putBytes(zos: ZipOutputStream, entryName: String, bytes: ByteArray, written: MutableSet<String>) {
        if (!written.add(entryName)) return
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(bytes)
        zos.closeEntry()
    }

    /** Copy every file under [dir] into the archive at `<prefix>/<relative-path>` (deterministic order). */
    private fun putTree(zos: ZipOutputStream, prefix: String, dir: Path, written: MutableSet<String>) {
        if (!Files.isDirectory(dir)) return
        val files = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.collect(Collectors.toList()) }
            .sortedBy { it.toString() }
        for (f in files) {
            val rel = dir.relativize(f).toString().replace('\\', '/')
            putFile(zos, "$prefix/$rel", f, written)
        }
    }

    private fun synthManifest(packageName: String): ByteArray =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$packageName\"/>\n")
            .toByteArray(Charsets.UTF_8)

    private fun metadataProperties(compileSdk: Int): ByteArray =
        ("aarFormatVersion=1.0\n" +
            "aarMetadataVersion=1.0\n" +
            "minCompileSdk=$compileSdk\n")
            .toByteArray(Charsets.UTF_8)
}

/**
 * The `bundleAar` step: package [outAar] from the library's compiled [classesJar], [manifest], raw
 * [resDirs]/[assetsDirs], the [rTxt] symbol table, native libs under [jniLibDirs], and consumer keep
 * rules (the readable files in [consumerProguardFiles] plus [inlineProguardRules]).
 */
internal class PackageAarTask(
    override val name: TaskName,
    private val classesJar: Path,
    private val manifest: Path,
    private val packageName: String,
    private val resDirs: List<Path>,
    private val rTxt: Path,
    private val assetsDirs: List<Path>,
    private val jniLibDirs: List<Path>,
    private val consumerProguardFiles: List<Path>,
    private val inlineProguardRules: List<String>,
    private val compileSdk: Int,
    private val outAar: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            if (Files.isRegularFile(classesJar)) filePaths("classes", listOf(classesJar))
            if (Files.isRegularFile(manifest)) filePaths("manifest", listOf(manifest))
            dirPaths("res", resDirs)
            dirPaths("assets", assetsDirs)
            dirPaths("jni", jniLibDirs)
            if (Files.isRegularFile(rTxt)) filePaths("rTxt", listOf(rTxt))
            filePaths("consumerProguard", consumerProguardFiles.filter { Files.isRegularFile(it) })
            property("package", packageName)
            property("inlineProguard", inlineProguardRules.joinToString("\n"))
            property("compileSdk", compileSdk.toString())
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("aar", outAar) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val proguard = buildString {
            consumerProguardFiles.filter { Files.isRegularFile(it) }.forEach { append(it.readText()).append('\n') }
            inlineProguardRules.forEach { append(it).append('\n') }
        }
        val entries = AarPackaging.assembleAar(
            classesJar, manifest, packageName, resDirs, rTxt, assetsDirs, jniLibDirs, proguard, compileSdk, outAar,
        )
        ctx.logger()("Packaged ${entries.size} entries into ${outAar.fileName}")
        return TaskResult.Success
    }
}
