package dev.ide.ksp

import kotlin.test.Test
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
}
