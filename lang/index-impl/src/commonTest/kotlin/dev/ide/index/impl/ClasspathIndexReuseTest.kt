package dev.ide.index.impl

import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.platform.createDirectories
import dev.ide.platform.writeFileAtomically
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A built index is reused when the same jars are opened again, wherever they now live.
 *
 * An iOS app's container gets a new path on every install, so the jars a project resolved last time are at a
 * different absolute path this time. The index must still be found: reading every jar again on each open is
 * the cost the cache exists to avoid.
 */
class ClasspathIndexReuseTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun jarUnder(dir: String): String {
        createDirectories(dir)
        val path = "$dir/kotlin-stdlib-fixture.jar"
        check(writeFileAtomically(path, Base64.decode(ClasspathFixture.JAR_BASE64)))
        return path
    }

    @Test
    fun theSameJarsAtANewPathReuseTheIndex() {
        val run = scratchPath("reuse-" + Random.nextLong().toULong().toString(16))
        val cache = "$run/cache"
        val indexes = listOf(KotlinCallableIndex)

        var firstReads = 0
        ClasspathIndex.build(listOf(jarUnder("$run/install-1/deps")), indexes, cache, onProgress = { done, _ -> firstReads = done })
        assertEquals(1, firstReads, "a fresh cache reads the jar")

        var secondReads = 0
        val moved = ClasspathIndex.build(listOf(jarUnder("$run/install-2/deps")), indexes, cache, onProgress = { _, _ -> secondReads++ })
        assertEquals(0, secondReads, "the same jar at a new path is not read again, nor reported as indexing")
        val hits = moved.prefix<Any>(KotlinCallableIndex.id, KotlinCallableIndex.topKey("print"), 10).toList()
        assertTrue(hits.isNotEmpty(), "the reused segment answers")
    }
}
