package dev.ide.lang.kotlin

import dev.ide.index.IndexOrigin
import dev.ide.index.IndexInput
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.platform.ContentHash
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Completeness + PRECISION proof for the `kotlin.callables` index, the gate for trusting a NEGATIVE conclusion
 * (an unresolved-import diagnostic on a lowercase callable import). The type check backs off on callables today
 * precisely because a bad negative here means a red error on valid code, so before any callable-import check can
 * exist we must show the index answers the exact question it would ask:
 *
 *   "Is there a top-level OR extension callable named `leaf` in package `pkg`?"
 *
 * This runs the REAL producer ([KotlinCallableIndex.index]) over the WHOLE stdlib jar — every `.class`, exactly
 * the real index's [KotlinCallableIndex.inputFilter], so `@JvmName`-renamed facades are covered too — and asserts:
 *  - POSITIVE: a spread of real stdlib imports across packages all resolve (so they would NOT be flagged);
 *  - NEGATIVE: a fabricated name does not resolve, AND a real name in the WRONG package does not resolve
 *    (so precision is by package+name, not name alone — `import kotlin.collections.println` is genuinely dead).
 */
class KotlinCallableIndexCompletenessTest {

    /** The exact predicate a callable-import check would use: a top-level (`top:`) OR extension (`name:`)
     *  callable of this simple [name] whose declaring package is [pkg]. */
    private fun callableResolves(pkg: String, name: String): Boolean {
        val top = served[KotlinCallableIndex.topKey(name)].orEmpty()
        val ext = served[KotlinCallableIndex.nameKey(name)].orEmpty()
        return (top + ext).any { it.packageName == pkg }
    }

    @Test
    fun realStdlibImportsResolve() {
        // package -> simple name, one representative per facade/package + both callable kinds.
        val realImports = listOf(
            "kotlin.io" to "println",       // top-level, ConsoleKt
            "kotlin.collections" to "listOf",   // top-level, CollectionsKt
            "kotlin.collections" to "emptyList", // top-level
            "kotlin" to "run",              // top-level, StandardKt (package `kotlin`)
            "kotlin" to "let",              // top-level
            "kotlin" to "TODO",             // top-level
            "kotlin.text" to "trim",        // extension, StringsKt
            "kotlin.text" to "uppercase",   // extension
            "kotlin.collections" to "map",  // extension, CollectionsKt
            "kotlin.collections" to "filter", // extension
        )
        val missing = realImports.filterNot { (pkg, name) -> callableResolves(pkg, name) }
        assertTrue(
            missing.isEmpty(),
            "these REAL stdlib imports must resolve through the callable index (else the diagnostic would " +
                "false-positive on valid code): $missing",
        )
    }

    @Test
    fun fabricatedImportDoesNotResolve() {
        assertFalse(callableResolves("kotlin.collections", "ghostFunctionXyz"),
            "a fabricated callable must not resolve (this is the import the diagnostic SHOULD flag)")
        assertFalse(callableResolves("com.example.nope", "doesNotExist"),
            "a callable in an unknown package must not resolve")
    }

    @Test
    fun resolutionIsPackagePreciseNotNameOnly() {
        // `println` is real, but lives in `kotlin.io` — `import kotlin.collections.println` is genuinely dead.
        // Proving name-alone would over-accept: this is why the type index's name-only check couldn't do callables.
        assertTrue(callableResolves("kotlin.io", "println"), "println is real in kotlin.io")
        assertFalse(callableResolves("kotlin.collections", "println"),
            "println must NOT resolve in the wrong package — the check is package-precise")
    }

    companion object {
        /** Real producer output over EVERY `.class` in the stdlib jar (matching the real inputFilter), keyed by
         *  the index's tagged key. Non-facade classes decode to no top-level/extension entries and contribute
         *  nothing, so this is the faithful set the disk index would hold for this artifact. */
        private val served: Map<String, List<CallableShape>> = buildServed()

        private fun buildServed(): Map<String, List<CallableShape>> {
            val out = HashMap<String, MutableList<CallableShape>>()
            ZipFile(stdlibJarPath().toFile()).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(".class")) continue
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    KotlinCallableIndex.index(FakeInput(e.name, bytes)).forEach { (k, v) ->
                        out.getOrPut(k) { ArrayList() }.addAll(v)
                    }
                }
            }
            return out
        }

        private class FakeInput(override val unitName: String, private val b: ByteArray) : IndexInput {
            override val origin = IndexOrigin.LIBRARY
            override val contentHash = ContentHash("")
            override val sourcePath: Path? = null
            override fun bytes() = b
            override fun text(): String? = null
            override fun dom() = null
        }
    }
}
