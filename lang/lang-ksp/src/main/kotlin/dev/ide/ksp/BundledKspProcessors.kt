package dev.ide.ksp

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Supplies the in-app-bundled KSP processor classpaths (Room, Moshi, Hilt/Dagger, Glide). Each processor's
 * transitive closure ships as a `zip-of-jars` classpath resource (`/processors/<id>.zip`, built by the
 * `ksp<Id>ProcessorZip` Gradle tasks — each entry is one jar, NOT merged, so no `META-INF/services`/
 * duplicate-class hazard; app-provided jars like kotlin-stdlib are dropped). This extracts a processor's jars
 * to a real dir on demand and returns them as a classpath.
 *
 * The jars are loaded through a [KspProcessorLoader] parented to our compiler/AA classloader, so a processor's
 * references to `symbol-processing-api`, the Kotlin stdlib, `com.intellij.*`, etc. resolve to OUR (parent)
 * versions first — the same contract [BundledKspThin] relies on. Wired as `KspProcessorCatalog.bundled`'s
 * `bundledJars`. These processors are EXECUTED by the IDE, so they are bundled (not downloaded) — Play DCL.
 *
 * The extraction dir is **content-keyed** (a hash of the zip resource), so a changed bundle — an app update
 * that ships a new processor version — re-extracts instead of serving a stale cache.
 */
object BundledKspProcessors {

    private val extracted = ConcurrentHashMap<String, List<Path>>()

    /** True when [id]'s processor bundle is on the classpath (false when this build didn't bundle it). */
    fun isBundled(id: String): Boolean = BundledKspProcessors::class.java.getResource("/processors/$id.zip") != null

    /** The bundled processor [id]'s jars, extracted to a process-wide content-keyed cache; empty when not bundled. */
    fun jarsFor(id: String): List<Path> = extracted.getOrPut(id) {
        val bytes = BundledKspProcessors::class.java.getResourceAsStream("/processors/$id.zip")
            ?.use { it.readBytes() } ?: return@getOrPut emptyList()
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "codeassist", "ksp-processors", "$id-${hash16(bytes)}")
        extractZipOfJars(bytes, dir)
    }

    /** Extract each jar entry of the zip [bytes] into [dir], reusing an already-extracted copy; return the jars. */
    private fun extractZipOfJars(bytes: ByteArray, dir: Path): List<Path> {
        Files.createDirectories(dir)
        val marker = dir.resolve(".extracted")
        if (!Files.isRegularFile(marker)) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".jar")) {
                        Files.copy(zis, dir.resolve(Paths.get(entry.name).fileName.toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            runCatching { Files.writeString(marker, "ok") }
        }
        return Files.list(dir).use { s -> s.filter { it.toString().endsWith(".jar") }.sorted().toList() }
    }

    private fun hash16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
