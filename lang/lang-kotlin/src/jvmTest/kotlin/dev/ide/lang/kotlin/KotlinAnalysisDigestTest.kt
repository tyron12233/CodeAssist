// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parity.KotlinAnalysisDigest
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.testkit.DiskVirtualFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Records what the Kotlin front end computes for a fixed corpus, so that the Android suite can prove it
 * computes the same thing on ART.
 *
 * The golden files are the CONTRACT between the two: `:ide-android`'s `KotlinAnalysisArtParityTest` reads
 * the identical corpus and the identical goldens out of its test assets and recomputes both digests through
 * the identical `KotlinAnalysisDigest`. A difference there is a platform difference, because everything else
 * is held equal by construction.
 *
 * This side is also a change detector in its own right: any edit to the parser, the symbol model, the
 * resolver or completion that moves the corpus shows up as a golden diff, which is a review artefact rather
 * than a surprise. Refresh with `-Dkt.updateDigest=true` and READ the diff before committing it.
 */
class KotlinAnalysisDigestTest {

    /**
     * The syntax digest of Kotlin's own parser corpus, one hash per file.
     *
     * Hashes rather than the digests themselves, because the digests of 757 files run to tens of megabytes
     * and no one reviews that. The file name is in the golden, so a divergence names the input; rerun with
     * `-Dkt.updateDigest=true` and diff the working tree to see what moved.
     */
    @Test
    fun theSyntaxDigestOfKotlinsOwnCorpusIsUnchanged() {
        val corpus = kotlinTestData()
        assertTrue(corpus.size > 700, "expected Kotlin's vendored parser corpus, found ${corpus.size} files")

        val recorded = corpus.joinToString("\n") { file ->
            val digest = KotlinAnalysisDigest.syntax(file.name, file.readText())
            "${file.toRelativeString(corpusRoot())}  ${ContentHash.of(digest).value}"
        } + "\n"

        compare(File(parityDir(), "syntax-digest.txt"), recorded)
    }

    /**
     * The semantic digest of a curated corpus: diagnostics, every reference's resolution, and what
     * completion offers after every `.`.
     *
     * Stored in full, unlike the syntax one, because it is three files and the content is the point -- a
     * reordered completion list or a reference that stopped resolving is legible in the diff.
     */
    @Test
    fun theSemanticDigestOfTheCuratedCorpusIsUnchanged() = runBlocking {
        val sources = corpusSources()
        assertEquals(3, sources.size, "the curated corpus is three files; found ${sources.map { it.name }}")

        // No classpath entries and no index: the standard library arrives from the bundled jar, which is
        // the SAME resource the app extracts on a device. Holding the fixture identical is what makes the
        // Android comparison about the platform.
        val service = KotlinSymbolService(
            sourceRoots = listOf(DiskVirtualFile(File(parityDir(), "corpus").toPath())),
            classpathJars = emptyList(),
        )
        // A loop, not `joinToString`: its `transform` is a NULLABLE function type, so the call is not
        // inlined and a suspend body cannot be passed to it.
        val recorded = try {
            buildString {
                for ((index, file) in sources.withIndex()) {
                    if (index > 0) append('\n')
                    append(KotlinAnalysisDigest.semantic(DiskVirtualFile(file.toPath()), file.readText(), service))
                }
            }
        } finally {
            service.close()
        }

        compare(File(parityDir(), "semantic-digest.txt"), recorded)
    }

    /** The digest must not depend on where the corpus sits on disk, or the Android side can never match. */
    @Test
    fun theDigestNamesNoAbsolutePath() {
        val golden = File(parityDir(), "semantic-digest.txt")
        if (!golden.isFile) return
        val text = golden.readText()
        assertTrue("/Users/" !in text && "/home/" !in text, "the golden leaked a machine path")
    }

    private fun compare(golden: File, recorded: String) {
        if (System.getProperty("kt.updateDigest") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(recorded)
            return
        }
        assertTrue(golden.isFile, "no golden at $golden — record one with -Dkt.updateDigest=true")
        val expected = golden.readText()
        if (expected == recorded) return
        assertEquals(expected, recorded, firstDifference(expected, recorded))
    }

    /** The first differing line, named, because a 757-line diff in an assertion message is unreadable. */
    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) {
            val left = e.getOrNull(i)
            val right = a.getOrNull(i)
            if (left != right) {
                return "line ${i + 1} differs:\n  golden: ${left ?: "<end of file>"}\n  now:    ${right ?: "<end of file>"}"
            }
        }
        return "the goldens differ in trailing content only"
    }

    private fun corpusSources(): List<File> =
        File(parityDir(), "corpus").listFiles().orEmpty().filter { it.extension == "kt" }.sortedBy { it.name }

    private fun kotlinTestData(): List<File> =
        corpusRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.invariantSeparatorsPath }.toList()

    private fun corpusRoot() = File(repoRoot(), "experimental/kotlin-syntax/testData")

    private fun parityDir() = File(repoRoot(), "lang/lang-kotlin/parity")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }
}
