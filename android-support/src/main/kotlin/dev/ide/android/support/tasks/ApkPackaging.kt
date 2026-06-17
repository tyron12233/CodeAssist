package dev.ide.android.support.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Pure-Java (no `jar`/`zip` binary needed) archive assembly for the Android pipeline. D8 won't dex a raw
 * class *directory*, so [jarClasses] packs compiled `.class` files into a jar to feed the dexer.
 * [assembleApk] is the `packageApk` step: it starts from aapt2's `resources.ap_` (binary manifest +
 * `resources.arsc` + compiled res), preserving each entry's original storage method so `resources.arsc`
 * stays uncompressed, then adds `classes*.dex` and any `assets/`.
 */
internal object ApkPackaging {

    /** Pack every `.class` under [classesDir] into [outJar] (deterministic order). */
    fun jarClasses(classesDir: Path, outJar: Path) {
        outJar.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(outJar)).use { zos ->
            if (!Files.isDirectory(classesDir)) return
            classFiles(classesDir).forEach { f ->
                zos.putNextEntry(ZipEntry(classesDir.relativize(f).toString().replace('\\', '/')))
                Files.copy(f, zos)
                zos.closeEntry()
            }
        }
    }

    /**
     * Build [outApk] from [resourcesAp] plus the dex files in [dexDir], the files under each
     * [assetsDirs] (mapped to `assets/…`), and the native libs under each [jniLibDirs] (mapped to
     * `lib/<abi>/…`, e.g. from exploded AARs). Returns the entry names written, for diagnostics.
     */
    fun assembleApk(
        resourcesAp: Path,
        dexDirs: List<Path>,
        assetsDirs: List<Path>,
        jniLibDirs: List<Path>,
        outApk: Path,
    ): List<String> {
        outApk.parent?.let { Files.createDirectories(it) }
        val written = LinkedHashSet<String>()
        ZipOutputStream(Files.newOutputStream(outApk)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)

            // 1) Everything aapt2 produced, preserving STORED/DEFLATED (keeps resources.arsc mmap-friendly).
            ZipFile(resourcesAp.toFile()).use { ap ->
                val entries = ap.entries().toList().sortedBy { it.name }
                for (e in entries) {
                    if (!written.add(e.name)) continue
                    val copy = ZipEntry(e.name).apply {
                        method = e.method
                        if (e.method == ZipEntry.STORED) { size = e.size; crc = e.crc }
                    }
                    zos.putNextEntry(copy)
                    ap.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // 2) classes.dex / classes2.dex … — collect every .dex across the dex dirs (native multidex packages
            // the project / lib / external layers separately) and renumber into one contiguous sequence.
            val dexFiles = dexDirs.filter { Files.isDirectory(it) }.flatMap { dir ->
                Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList()) }
            }
            dexFiles.forEachIndexed { i, dex ->
                val name = if (i == 0) "classes.dex" else "classes${i + 1}.dex"
                if (written.add(name)) putDeflated(zos, name, Files.readAllBytes(dex))
            }

            // 3) assets/**
            for (dir in assetsDirs.filter { Files.isDirectory(it) }) {
                allFiles(dir).forEach { f ->
                    val name = "assets/" + dir.relativize(f).toString().replace('\\', '/')
                    if (written.add(name)) putDeflated(zos, name, Files.readAllBytes(f))
                }
            }

            // 4) native libs: an AAR's jni/<abi>/*.so maps to the APK's lib/<abi>/…
            for (dir in jniLibDirs.filter { Files.isDirectory(it) }) {
                allFiles(dir).forEach { f ->
                    val name = "lib/" + dir.relativize(f).toString().replace('\\', '/')
                    if (written.add(name)) putDeflated(zos, name, Files.readAllBytes(f))
                }
            }
        }
        return written.toList()
    }

    private fun putDeflated(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun classFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().collect(Collectors.toList()) }

    private fun allFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList()) }
}
