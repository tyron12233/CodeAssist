package dev.ide.android.support.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Assembles a `bundletool` *base module zip* — the input `bundletool build-bundle` turns into the `base/`
 * module of an `.aab`. The layout differs from an APK: the manifest lives under `manifest/`, resources are
 * the proto form (`resources.pb` + compiled `res`) produced by `aapt2 link --proto-format`, dex under
 * `dex`, and assets/native-libs under `assets`/`lib`.
 *
 *     manifest/AndroidManifest.xml   (proto-encoded, from the proto resources.ap_)
 *     resources.pb                   (proto resource table)
 *     res/...                        (compiled resources)
 *     dex/classes-N.dex
 *     assets/...
 *     lib/abi/...
 *     root/...                       (anything else from the linked apk)
 */
internal object BundlePackaging {

    fun buildBaseModuleZip(
        protoAp: Path,
        dexDirs: List<Path>,
        assetsDirs: List<Path>,
        jniLibDirs: List<Path>,
        outZip: Path,
    ): List<String> {
        outZip.parent?.let { Files.createDirectories(it) }
        val written = LinkedHashSet<String>()
        ZipOutputStream(Files.newOutputStream(outZip)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)

            // 1) Remap the proto-linked apk into the module layout.
            ZipFile(protoAp.toFile()).use { ap ->
                for (e in ap.entries().toList().sortedBy { it.name }) {
                    val target = when {
                        e.isDirectory -> continue
                        e.name == "AndroidManifest.xml" -> "manifest/AndroidManifest.xml"
                        e.name == "resources.pb" -> "resources.pb"
                        e.name.startsWith("res/") -> e.name
                        else -> "root/${e.name}"   // e.g. any stray top-level file aapt2 emitted
                    }
                    if (!written.add(target)) continue
                    zos.putNextEntry(ZipEntry(target).apply { method = ZipEntry.DEFLATED })
                    ap.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // 2) dex/classes*.dex — renumber every .dex across the merged layers (project/lib/ext) contiguously.
            val dexFiles = dexDirs.filter { Files.isDirectory(it) }.flatMap { dir ->
                Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList()) }
            }
            dexFiles.forEachIndexed { i, dex ->
                val name = "dex/" + if (i == 0) "classes.dex" else "classes${i + 1}.dex"
                if (written.add(name)) putDeflated(zos, name, dex)
            }

            // 3) assets/** and 4) lib/<abi>/**
            for (dir in assetsDirs.filter { Files.isDirectory(it) }) copyTree(zos, dir, "assets/", written)
            for (dir in jniLibDirs.filter { Files.isDirectory(it) }) copyTree(zos, dir, "lib/", written)
        }
        return written.toList()
    }

    private fun copyTree(zos: ZipOutputStream, dir: Path, prefix: String, written: MutableSet<String>) {
        allFiles(dir).forEach { f ->
            val name = prefix + dir.relativize(f).toString().replace('\\', '/')
            if (written.add(name)) putDeflated(zos, name, f)
        }
    }

    /** STREAM the file into the zip (8 KB buffer) rather than reading it whole into a ByteArray — keeps heap
     *  flat regardless of entry size (native `.so` libs / large assets are the biggest files in a bundle). */
    private fun putDeflated(zos: ZipOutputStream, name: String, file: Path) {
        zos.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        Files.newInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun allFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList()) }
}
