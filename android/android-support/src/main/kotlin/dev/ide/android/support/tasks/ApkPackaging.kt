package dev.ide.android.support.tasks

import dev.ide.android.support.AndroidPackaging
import dev.ide.android.support.tools.ArtProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Pure-Java (no `jar`/`zip` binary needed) archive assembly for the Android pipeline. D8 won't dex a raw
 * class *directory*, so [jarClasses] packs compiled `.class` files into a jar to feed the dexer.
 * [assembleApk] is the `packageApk` step: it starts from aapt2's `resources.ap_` (binary manifest +
 * `resources.arsc` + compiled res), preserving each entry's original storage method so `resources.arsc`
 * stays uncompressed, then adds `classes*.dex` and any `assets/`. An entry the module's no-compress rule
 * matches ([AndroidPackaging.isNoCompressed]) is STORED rather than deflated.
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
     * The dex files across [dexDirs] in the order they are packaged, each with its packaged name
     * (`classes.dex`, `classes2.dex`, …). Native multidex packages the project / lib / external layers
     * separately, so every layer's files are renumbered into one contiguous sequence. The APK, the bundle and
     * the Baseline Profile compiler all take the order from here: a profile names each dex by the name it is
     * packaged under, so the three have to agree.
     */
    fun packagedDex(dexDirs: List<Path>): List<Pair<String, Path>> =
        dexDirs.filter { Files.isDirectory(it) }.flatMap { dir ->
            Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList()) }
        }.mapIndexed { i, dex -> (if (i == 0) "classes.dex" else "classes${i + 1}.dex") to dex }

    /**
     * Build [outApk] from [resourcesAp] plus the dex files in [dexDir], the files under each
     * [assetsDirs] (mapped to `assets/…`), the native libs under each [jniLibDirs] (mapped to
     * `lib/<abi>/…`), and the merged Java resources in each of [javaResJars] (their entries copied to the
     * APK root). aapt2's output is written first, so a Java resource can never shadow the manifest /
     * `resources.arsc` / `res/`. [artProfile] is the compiled Baseline Profile pair (`.prof`, `.profm`) when
     * the build made one. [noCompress] says which entry paths are STORED (AGP's `androidResources.noCompress`
     * plus its defaults); it is applied to aapt2's entries too, so a `res/raw` file with a listed extension is
     * stored even though aapt2 deflated it. Returns the entry names written, for diagnostics.
     */
    fun assembleApk(
        resourcesAp: Path,
        dexDirs: List<Path>,
        assetsDirs: List<Path>,
        jniLibDirs: List<Path>,
        outApk: Path,
        javaResJars: List<Path> = emptyList(),
        artProfile: Pair<Path, Path>? = null,
        noCompress: (String) -> Boolean = AndroidPackaging()::isNoCompressed,
        /** Deflate level for entries this step compresses itself. A debug build trades a slightly larger APK for
         *  a faster package (and faster edit-and-run); a release build keeps the smallest. */
        compressionLevel: Int = Deflater.BEST_COMPRESSION,
    ): List<String> {
        outApk.parent?.let { Files.createDirectories(it) }
        runCatching { assembleFast(resourcesAp, dexDirs, assetsDirs, jniLibDirs, outApk, javaResJars, artProfile, noCompress, compressionLevel) }
            .getOrNull()?.let { return it }
        return assembleSlow(resourcesAp, dexDirs, assetsDirs, jniLibDirs, outApk, javaResJars, artProfile, noCompress)
    }

    /** How many entries the last fast assembly reused from the previous APK and how many it wrote (diagnostics). */
    @Volatile var lastReuse: Pair<Int, Int> = 0 to 0

    /**
     * The packager without recompression: aapt2's and the Java resource jars' entries are copied as the compressed
     * bytes they already are, and a file whose bytes match the previous APK's entry of that name (size, CRC and
     * storage) is copied out of the previous APK, so an edit that changed one `classes*.dex` deflates only that one.
     * Same entries, order and storage as [assembleSlow]. Null when the inputs need something it doesn't do (a
     * deflated aapt2 entry the no-compress rule wants STORED, zip64): the caller then packages the slow way.
     */
    private fun assembleFast(
        resourcesAp: Path, dexDirs: List<Path>, assetsDirs: List<Path>, jniLibDirs: List<Path>, outApk: Path,
        javaResJars: List<Path>, artProfile: Pair<Path, Path>?, noCompress: (String) -> Boolean, level: Int,
    ): List<String>? {
        val ap = ZipIndex.read(resourcesAp) ?: return null
        val jars = javaResJars.filter { Files.isRegularFile(it) && Files.size(it) > 22 }.map { ZipIndex.read(it) ?: return null }
        val previous = if (Files.isRegularFile(outApk)) ZipIndex.read(outApk) else null
        val crcs = CrcCache(outApk.resolveSibling("." + outApk.fileName.toString() + ".crcs"))
        val tmp = outApk.resolveSibling(outApk.fileName.toString() + ".tmp")
        val written = LinkedHashSet<String>()
        var reused = 0
        var fresh = 0
        try {
            ApkZipWriter(tmp, level).use { w ->
                fun put(name: String, file: Path, stored: Boolean) {
                    val prev = previous?.entries?.get(name)
                    if (prev != null && prev.size == Files.size(file) && (prev.method == ZipEntry.STORED) == stored &&
                        prev.crc == crcs.crcOf(file)
                    ) { w.copyRaw(previous, prev); reused++ } else { w.putFile(name, file, stored); fresh++ }
                }
                fun copy(from: ZipIndex, e: ZipIndex.Entry): Boolean {
                    if (noCompress(e.name) && e.method != ZipEntry.STORED) return false
                    w.copyRaw(from, e); return true
                }
                // 1) Everything aapt2 produced, storage kept as aapt2 chose it.
                for (e in ap.entries.values.sortedBy { it.name }) {
                    if (written.add(e.name) && !copy(ap, e)) return null
                }
                // 2) classes.dex / classes2.dex …
                for ((name, dex) in packagedDex(dexDirs)) if (written.add(name)) put(name, dex, noCompress(name))
                // 3) The compiled Baseline Profile (STORED; see [assembleSlow]).
                artProfile?.let { (prof, profm) ->
                    for ((name, file) in listOf(ArtProfiles.APK_PROFILE to prof, ArtProfiles.APK_PROFILE_METADATA to profm)) {
                        if (Files.isRegularFile(file) && written.add(name)) put(name, file, stored = true)
                    }
                }
                // 4) assets/**
                for (dir in assetsDirs.filter { Files.isDirectory(it) }) allFiles(dir).forEach { f ->
                    val name = "assets/" + dir.relativize(f).toString().replace('\\', '/')
                    if (written.add(name)) put(name, f, noCompress(name))
                }
                // 5) native libs
                for (dir in jniLibDirs.filter { Files.isDirectory(it) }) allFiles(dir).forEach { f ->
                    val name = "lib/" + dir.relativize(f).toString().replace('\\', '/')
                    if (written.add(name)) put(name, f, noCompress(name))
                }
                // 6) Java resources, aapt2's output winning a collision.
                for (jar in jars) for (e in jar.entries.values.sortedBy { it.name }) {
                    if (written.add(e.name) && !copy(jar, e)) return null
                }
            }
            Files.move(tmp, outApk, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            crcs.save()
        } finally {
            Files.deleteIfExists(tmp)
        }
        lastReuse = reused to fresh
        return written.toList()
    }

    /** The original packager: every entry goes through [ZipOutputStream], copied ones inflated and re-deflated. */
    private fun assembleSlow(
        resourcesAp: Path, dexDirs: List<Path>, assetsDirs: List<Path>, jniLibDirs: List<Path>, outApk: Path,
        javaResJars: List<Path>, artProfile: Pair<Path, Path>?, noCompress: (String) -> Boolean,
    ): List<String> {
        val written = LinkedHashSet<String>()
        ZipOutputStream(Files.newOutputStream(outApk)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)

            // 1) Everything aapt2 produced, preserving STORED (keeps resources.arsc mmap-friendly). A deflated
            //    entry is stored when the no-compress rule names it.
            ZipFile(resourcesAp.toFile()).use { ap ->
                val entries = ap.entries().toList().sortedBy { it.name }
                for (e in entries) {
                    if (!written.add(e.name)) continue
                    copyEntry(zos, ap, e, stored = e.method == ZipEntry.STORED || noCompress(e.name))
                }
            }

            // 2) classes.dex / classes2.dex …
            for ((name, dex) in packagedDex(dexDirs)) {
                if (written.add(name)) putFile(zos, name, dex, noCompress(name))
            }

            // 3) The compiled Baseline Profile, ahead of assets/** so a stale copy under a source `assets/dexopt/`
            //    cannot take its place. STORED: profileinstaller opens it as a file descriptor, which a
            //    compressed entry cannot give, and then installs nothing.
            artProfile?.let { (prof, profm) ->
                for ((name, file) in listOf(ArtProfiles.APK_PROFILE to prof, ArtProfiles.APK_PROFILE_METADATA to profm)) {
                    if (Files.isRegularFile(file) && written.add(name)) putFile(zos, name, file, stored = true)
                }
            }

            // 4) assets/**
            for (dir in assetsDirs.filter { Files.isDirectory(it) }) {
                allFiles(dir).forEach { f ->
                    val name = "assets/" + dir.relativize(f).toString().replace('\\', '/')
                    if (written.add(name)) putFile(zos, name, f, noCompress(name))
                }
            }

            // 5) native libs: an AAR's jni/<abi>/*.so maps to the APK's lib/<abi>/…
            for (dir in jniLibDirs.filter { Files.isDirectory(it) }) {
                allFiles(dir).forEach { f ->
                    val name = "lib/" + dir.relativize(f).toString().replace('\\', '/')
                    if (written.add(name)) putFile(zos, name, f, noCompress(name))
                }
            }

            // 6) Java resources (already merged + filtered by MergeJavaResourcesTask): entries copied to the
            //    APK root. aapt2's output was written first, so it always wins a collision (dedup below).
            for (jar in javaResJars.filter { Files.isRegularFile(it) }) {
                // A merged-java-res.jar with no entries is the 22-byte empty archive, which ART's ZipFile
                // rejects on read; skip an empty/unreadable jar (it packages nothing) instead of crashing.
                openZipOrNull(jar)?.use { zf ->
                    val entries = zf.entries().toList().filter { !it.isDirectory }.sortedBy { it.name }
                    for (e in entries) {
                        if (!written.add(e.name)) continue
                        copyEntry(zos, zf, e, stored = noCompress(e.name))
                    }
                }
            }
        }
        return written.toList()
    }

    /** STREAM the file into the zip (8 KB buffer) rather than reading it whole into a ByteArray — keeps heap
     *  flat regardless of entry size (native `.so` libs / large assets are the biggest files in an APK). A
     *  STORED entry needs its size and CRC before the data, so it is read twice: once for the CRC, then copied. */
    private fun putFile(zos: ZipOutputStream, name: String, file: Path, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            val crc = CRC32()
            val buf = ByteArray(64 * 1024)
            Files.newInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            entry.storedAs(Files.size(file), crc.value)
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        Files.newInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** Copy [e] out of [zip] under its own name. A ZipFile entry's size and CRC come from the central directory,
     *  so storing it needs no extra pass. */
    private fun copyEntry(zos: ZipOutputStream, zip: ZipFile, e: ZipEntry, stored: Boolean) {
        val copy = ZipEntry(e.name)
        if (stored) copy.storedAs(e.size, e.crc) else copy.method = ZipEntry.DEFLATED
        zos.putNextEntry(copy)
        zip.getInputStream(e).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun ZipEntry.storedAs(length: Long, checksum: Long) {
        method = ZipEntry.STORED
        size = length
        compressedSize = length
        crc = checksum
    }

    private fun classFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().collect(Collectors.toList()) }

    private fun allFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList()) }
}
