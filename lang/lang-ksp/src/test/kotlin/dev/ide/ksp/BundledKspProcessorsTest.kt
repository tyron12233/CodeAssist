package dev.ide.ksp

import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies every blessed processor is bundled in-app (`/processors/<id>.zip` present + extracts its own
 * closure) and that the APK-size dedup dropped the app-provided jars. Packaging only — running each is covered
 * by [BundledRoomProcessorTest] (Room) and [MoshiKspTest] (Moshi).
 */
class BundledKspProcessorsTest {

    @Test
    fun allBlessedProcessorsAreBundledAndDeduped() {
        // Each bundle's own processor jar (proves the right closure was packaged).
        val ownJar = mapOf(
            "room" to "room-compiler",
            "moshi" to "moshi-kotlin-codegen",
            "hilt" to "hilt-compiler",
            "glide" to "ksp",   // com.github.bumptech.glide:ksp → ksp-<ver>.jar
        )
        for ((id, marker) in ownJar) {
            assertTrue(BundledKspProcessors.isBundled(id), "/processors/$id.zip is missing — did ksp${id}ProcessorZip run?")
            val jars = BundledKspProcessors.jarsFor(id)
            assertTrue(jars.size >= 2, "$id bundle looks too small: ${jars.map { it.fileName }}")
            assertTrue(
                jars.any { it.fileName.toString().startsWith(marker) || it.fileName.toString().startsWith("$marker-") },
                "$id bundle is missing its processor jar ($marker*). Got: ${jars.map { it.fileName }}",
            )
            // APK-size dedup: the app already ships these, so a bundle must NOT re-package them.
            val leaked = jars.map { it.fileName.toString() }.filter {
                it.startsWith("kotlin-stdlib") || it.startsWith("kotlinx-coroutines") || it.startsWith("symbol-processing-api")
            }
            assertTrue(leaked.isEmpty(), "$id bundle re-ships app-provided jars (should be deduped): $leaked")
        }
    }

    /**
     * A jar that two closures share ships once in `/processors/shared.zip` rather than inside each bundle, so
     * a bundle's classpath is only complete if the loader extracts that zip too. Room's closure needs guava
     * and kotlin-reflect and packages neither itself; if `shared.zip` stopped being built, or stopped being
     * extracted, the processor would fail at run time with a NoClassDefFoundError deep inside code generation
     * rather than here.
     */
    @Test
    fun aSharedJarReachesTheBundleThatNeedsIt() {
        val jars = BundledKspProcessors.jarsFor("room").map { it.fileName.toString() }
        for (shared in listOf("guava-", "kotlin-reflect-")) {
            assertTrue(jars.any { it.startsWith(shared) }, "room's classpath is missing $shared*. Got: $jars")
        }
    }

    /**
     * A bundle's classpath is its own jars plus exactly the shared jars it claimed, and nothing else.
     *
     * `shared.zip` is one pool serving five closures that disagree about versions by a lot: guava is 30.1.1 in
     * moshi, 33.2.1 in room and 33.6.0 in hilt, and kotlin-reflect spans 1.6.10 to 2.0.10. So a loader that
     * unpacked the whole pool into every bundle would drop a second guava beside hilt's own and leave
     * `Files.list().sorted()` to decide which one the processor compiles against. That is not a packaging
     * error anyone would see; it is a processor quietly running on the wrong library. `shared.list` is what
     * keeps each bundle to its own share, and this pins the loader to it.
     */
    @Test
    fun aBundleTakesOnlyTheSharedJarsItClaimed() {
        for (id in listOf("room", "room3", "moshi", "hilt", "glide")) {
            if (!BundledKspProcessors.isBundled(id)) continue
            val own = mutableSetOf<String>()
            var claimed = emptySet<String>()
            ZipInputStream(javaClass.getResourceAsStream("/processors/$id.zip")!!).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    when {
                        e.name.endsWith(".jar") -> own += e.name.substringAfterLast('/')
                        e.name == "shared.list" -> claimed = zis.readBytes().decodeToString()
                            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            val actual = BundledKspProcessors.jarsFor(id).map { it.fileName.toString() }.toSet()
            assertEquals(own + claimed, actual, "$id's extracted classpath is not its own jars plus its claimed share")
        }
    }
}
