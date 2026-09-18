// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.android.parity

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.lang.kotlin.parity.KotlinAnalysisDigest
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Kotlin front end produces the same analysis on ART as it does on the JVM.
 *
 * This is the claim the multiplatform port made implicit and nothing checked. The editor's parser, symbol
 * model, resolver and completion are one body of `commonMain` code that the phone runs as dex and the desktop
 * runs as class files, and "it is the same code" is not the same statement as "it behaves the same". Three
 * things break that equivalence, and all three have shipped to users before:
 *
 * 1. **A JDK method ART does not have.** D8 dexes it into a synthetic outline that throws `NoSuchMethodError`
 *    when the line is first reached; inside a `runCatching` it is swallowed, and the feature quietly returns
 *    the wrong answer instead of failing. `AndroidApiFloorTest` guards the source textually; this catches
 *    what the text scan cannot see, including one inside a dependency.
 * 2. **A different `HashMap`.** ART's iteration order is its own, so anything enumerated from a hash-keyed
 *    collection can come back in a different order — which for completion IS the product.
 * 3. **A different `Collator` or default locale**, which reorders anything sorted by name.
 *
 * The suite reads the corpus and the goldens out of its own assets, staged from the same directories the JVM
 * suite records from (see `ide-android/build.gradle.kts`), and recomputes both digests through the same
 * `KotlinAnalysisDigest`. Everything but the platform is held equal, so a diff has one explanation.
 *
 * Run: `./gradlew :ide-android:connectedDebugAndroidTest --tests '*KotlinAnalysisArtParityTest*'`.
 * Worth running on an API 26 image as well as a current one — the floor is where the missing-method class of
 * bug actually throws, and a modern image has every method the code could want.
 */
@RunWith(AndroidJUnit4::class)
class KotlinAnalysisArtParityTest {

    /** The INSTRUMENTATION context: the corpus is an asset of the test APK, not of the app under test. */
    private val assets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun theSyntaxDigestOfKotlinsOwnCorpusMatchesTheJvm() {
        val golden = readAsset("syntax-digest.txt").trim().lines()
        assertTrue("no syntax golden in the test assets", golden.size > 700)

        val mismatched = mutableListOf<String>()
        for (line in golden) {
            val path = line.substringBefore("  ")
            val expected = line.substringAfterLast("  ")
            val source = readAsset(path)
            val actual = ContentHash.of(KotlinAnalysisDigest.syntax(File(path).name, source)).value
            if (actual != expected) mismatched += path
        }
        assertTrue(
            "ART parsed these differently from the JVM (${mismatched.size} of ${golden.size}):\n" +
                mismatched.take(20).joinToString("\n"),
            mismatched.isEmpty(),
        )
    }

    @Test
    fun theSemanticDigestOfTheCuratedCorpusMatchesTheJvm() = runBlocking {
        val golden = readAsset("semantic-digest.txt")
        val root = stageCorpus()
        val sources = root.listFiles().orEmpty().filter { it.extension == "kt" }.sortedBy { it.name }
        assertEquals("the curated corpus did not stage", 3, sources.size)

        val vfs = LocalFileSystem(root.toPath())
        // No classpath and no index, exactly as the JVM side: the standard library comes from the bundled
        // jar, which on this platform is the very same resource the app extracts at runtime.
        val service = KotlinSymbolService(sourceRoots = listOf(vfs.root()), classpathJars = emptyList())
        val recorded = try {
            buildString {
                for ((index, file) in sources.withIndex()) {
                    if (index > 0) append('\n')
                    append(KotlinAnalysisDigest.semantic(vfs.fileFor(file.path), file.readText(), service))
                }
            }
        } finally {
            service.close()
        }

        if (golden != recorded) {
            val e = golden.lines()
            val a = recorded.lines()
            val at = (0 until maxOf(e.size, a.size)).firstOrNull { e.getOrNull(it) != a.getOrNull(it) }
            assertEquals(
                "ART and the JVM disagree at line ${(at ?: 0) + 1}",
                e.getOrNull(at ?: 0), a.getOrNull(at ?: 0),
            )
        }
    }

    /**
     * Copy the curated corpus out of the assets onto the filesystem.
     *
     * The symbol model reads source through a `VirtualFile` over a real directory — it walks and stats — and
     * an `AssetManager` entry is neither. Staging is also what makes the two sides comparable: the JVM reads
     * the same three files from the repo.
     */
    private fun stageCorpus(): File {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "parity-corpus")
        dir.deleteRecursively()
        dir.mkdirs()
        for (name in assets.list("corpus").orEmpty()) {
            File(dir, name).writeText(readAsset("corpus/$name"))
        }
        return dir
    }

    private fun readAsset(path: String): String =
        assets.open(path).use { it.readBytes().decodeToString() }
}
