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

    /**
     * The bundled processor [id]'s jars, extracted to a process-wide content-keyed cache; empty when not
     * bundled.
     *
     * Two resources make up a bundle. `/processors/<id>.zip` holds what only this processor needs;
     * `/processors/shared.zip` holds the jars two or more closures resolved to the same artifact for, which
     * ship once rather than once per processor.
     *
     * A bundle takes back only the shared jars IT contributed, listed by name in its own `shared.list` entry.
     * Taking all of `shared.zip` would be wrong: these closures disagree wildly about versions (guava is
     * 30.1.1 in moshi, 33.2.1 in room, 33.6.0 in hilt), so the shared copy would land beside a bundle's own
     * and leave sort order to pick which one the processor loads. With the list, the extracted dir holds
     * exactly the jars it held when every bundle was self-contained.
     *
     * The key covers both zips, so an app update that changes either re-extracts instead of serving a stale
     * cache.
     */
    fun jarsFor(id: String): List<Path> = extracted.getOrPut(id) {
        val bytes = resource("/processors/$id.zip") ?: return@getOrPut emptyList()
        val wanted = sharedNamesIn(bytes)
        // Absent in a build where no two closures overlapped, and in any older bundle layout.
        val shared = if (wanted.isEmpty()) null else resource("/processors/shared.zip")
        val key = "$id-${hash16(bytes)}" + (shared?.let { "-${hash16(it)}" } ?: "")
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "codeassist", "ksp-processors", key)
        val sources = listOfNotNull(
            bytes to { name: String -> name.endsWith(".jar") },
            shared?.let { it to { name: String -> name in wanted } },
        )
        extractZipsOfJars(sources, dir)
    }

    private fun resource(path: String): ByteArray? =
        BundledKspProcessors::class.java.getResourceAsStream(path)?.use { it.readBytes() }

    /** The shared-jar names a bundle claims, from its `shared.list` entry; empty when it claims none. */
    private fun sharedNamesIn(bundle: ByteArray): Set<String> {
        ZipInputStream(ByteArrayInputStream(bundle)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "shared.list") {
                    return zis.readBytes().decodeToString().lineSequence()
                        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return emptySet()
    }

    /** Extract the entries each zip in [sources] accepts into [dir], reusing an already-extracted copy. */
    private fun extractZipsOfJars(sources: List<Pair<ByteArray, (String) -> Boolean>>, dir: Path): List<Path> {
        Files.createDirectories(dir)
        val marker = dir.resolve(".extracted")
        if (!Files.isRegularFile(marker)) {
            sources.forEach { (bytes, accept) ->
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = Paths.get(entry.name).fileName.toString()
                        if (!entry.isDirectory && name.endsWith(".jar") && accept(name)) {
                            Files.copy(zis, dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            runCatching { Files.writeString(marker, "ok") }
        }
        return Files.list(dir).use { s -> s.filter { it.toString().endsWith(".jar") }.sorted().toList() }
    }

    private fun hash16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
