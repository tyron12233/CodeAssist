package dev.ide.android

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared on-device dex staging for the console-run path — used by both [DexClassLoaderRunner] (in-process)
 * and [ForkedDalvikRunner] (forked `dalvikvm`).
 *
 * **Why staging is needed (the writable-dex fix):** ART refuses to load a dex the current process can still
 * write — in-process (`Writable dex file '…' is not allowed`, W^X) and on a command-line VM's classpath
 * alike. The cure is clearing the file's write bits, but the `dexRun` build output lives under the project
 * dir, which on device is **app-specific external storage** (an emulated FUSE/sdcardfs mount) where `chmod`
 * is a silent no-op — the file stays writable and ART rejects it. So the dex is copied into internal `ext4`
 * storage (where clearing the write bit actually sticks), made read-only there, verified, and loaded from
 * there. The source `dex-run/dex` is left writable so the next incremental `dexRun` can overwrite it.
 */
object DexStaging {

    /** Every `.dex` under [dexDir], sorted (libraries first — the dexer lays them down before user classes). */
    fun collectDexes(dexDir: Path): List<Path> =
        if (!Files.isDirectory(dexDir)) emptyList()
        else Files.walk(dexDir).use { s ->
            s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
        }

    /**
     * Copy [sources] into [stagingDir] (which MUST be on internal storage) under stable multidex names
     * (`classes.dex`, `classes2.dex`, …, in load order), make each read-only there, and verify the write bit
     * actually cleared. Prior staged dexes are cleared first so a removed library can't leave a stale
     * `classesN.dex` on the load path. Rewriting identical bytes keeps the dex's own checksum, so ART's
     * compiled oat for an unchanged dex stays valid and is reused. Returns null (after logging via [log]) if a
     * dex can't be staged read-only — the only safe outcome, since ART would reject a writable one.
     */
    fun stageReadOnly(stagingDir: File, sources: List<Path>, log: (String) -> Unit): List<File>? {
        stagingDir.mkdirs()
        // Clear prior staged dexes. They're read-only, but deleting only needs write on the directory (which
        // this internal-storage dir grants), so a shrinking dex set can't leave stale files on the load path.
        stagingDir.listFiles { f -> f.name.endsWith(".dex") }
            ?.forEach { runCatching { it.setWritable(true, false); it.delete() } }
        val out = ArrayList<File>(sources.size)
        sources.forEachIndexed { i, src ->
            val dest = File(stagingDir, if (i == 0) "classes.dex" else "classes${i + 1}.dex")
            try {
                src.toFile().inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            } catch (t: Throwable) {
                log("Cannot stage dex ${src.fileName}: ${t.message ?: t}"); return null
            }
            dest.setWritable(false, false)
            if (dest.canWrite()) {
                log("Fatal: cannot make staged dex read-only (${dest.absolutePath}). The run cache filesystem does not honor permissions.")
                return null
            }
            out.add(dest)
        }
        return out
    }

    /**
     * Copy [sources] into a SINGLE read-only container zip at [container] (which MUST be on internal storage),
     * their dexes stored under stable multidex names (`classes.dex`, `classes2.dex`, …, in load order), then
     * make the container read-only there and verify the write bit actually cleared. Returns the container, or
     * null (after logging via [log]) if it can't be made read-only — the only safe outcome, since ART rejects a
     * writable dex/container on a command-line VM's classpath (W^X).
     *
     * **Why one container instead of N staged dexes** ([ForkedDalvikRunner]): the forked `dalvikvm` takes its
     * classpath as one `-cp <a:b:c…>` command-line argument, and a large dependency graph produces *hundreds*
     * of `classes*.dex` (the run dexer lays one dex down per library). Joining them all into one argument
     * overflows the OS argument limit — `execve` fails with `E2BIG` ("Argument list too long"). ART loads every
     * `classesN.dex` entry from a zip/apk on the classpath (standard multidex — this is how an APK loads), so a
     * single container is equivalent to N paths while keeping the command line short regardless of dep count.
     *
     * Entries are DEFLATE-compressed: ART decompresses a zipped dex into memory when loading it from a
     * classpath zip, so no page-alignment dance is needed, and the dex's own checksum (over the decompressed
     * bytes) is unchanged, so ART's compiled-oat reuse for an unchanged program still holds.
     */
    fun stageReadOnlyContainer(container: File, sources: List<Path>, log: (String) -> Unit): File? {
        if (sources.isEmpty()) { log("No dex to stage."); return null }
        container.parentFile?.mkdirs()
        // A prior container is read-only; deleting only needs write on the directory (internal storage grants
        // it), so a shrinking dex set can't leave a stale container on the load path.
        runCatching { container.setWritable(true, false); container.delete() }
        try {
            ZipOutputStream(container.outputStream().buffered()).use { zos ->
                sources.forEachIndexed { i, src ->
                    zos.putNextEntry(ZipEntry(if (i == 0) "classes.dex" else "classes${i + 1}.dex"))
                    src.toFile().inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        } catch (t: Throwable) {
            log("Cannot stage run container: ${t.message ?: t}"); return null
        }
        container.setWritable(false, false)
        if (container.canWrite()) {
            log("Fatal: cannot make staged run container read-only (${container.absolutePath}). The run cache filesystem does not honor permissions.")
            return null
        }
        return container
    }
}
