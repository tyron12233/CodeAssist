package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.PreviewLoweringDiskCache
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.KotlinResolverCaches
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The disk-persisted preview-lowering cache: a lowered program written by one [KotlinPreviewLowering] instance
 * is served by DECODE to a fresh instance (the project-reopen path), under the same validity gates as the
 * in-memory cache (file signature hash; per-declaration text+offset; classpath salt). "Was anything
 * re-lowered?" is observed through `cachesFor`, which fires exactly when a [KotlinTreeResolver] is built —
 * and one is only built for a file that materializes at least one FRESH declaration.
 */
class PreviewLoweringDiskCacheTest {

    private val entryText = """
        package com.example
        fun entry(): String = helper("x")
    """.trimIndent()

    private val helperText = """
        package com.example
        fun helper(s: String): String = listOf(s).first()
    """.trimIndent()

    private fun parsedEntry(dir: Path): KotlinParsedFile =
        KotlinParsedFile(KotlinParserHost.parse("Entry.kt", entryText), DiskFile(dir.resolve("Entry.kt")), 0)

    private fun project(): Path = tempProject(mapOf("Entry.kt" to entryText, "Helper.kt" to helperText))

    private fun lowering(
        dir: Path, cacheDir: Path, salt: String, resolverBuilds: AtomicInteger,
    ): KotlinPreviewLowering = KotlinPreviewLowering(
        KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath())),
        cachesFor = { resolverBuilds.incrementAndGet(); KotlinResolverCaches() },
        diskCache = PreviewLoweringDiskCache(cacheDir, salt),
    )

    @Test
    fun restartServesLoweredDeclarationsFromDiskWithoutReResolving() {
        val dir = project()
        val cacheDir = Files.createTempDirectory("plc-test")

        // Session 1: cold — resolvers are built, the lowering persists.
        val builds1 = AtomicInteger()
        val cache1 = PreviewLoweringDiskCache(cacheDir, "salt")
        val lowering1 = KotlinPreviewLowering(
            KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath())),
            cachesFor = { builds1.incrementAndGet(); KotlinResolverCaches() },
            diskCache = cache1,
        )
        val model1 = lowering1.crossFileModel(parsedEntry(dir))
        assertNotNull(model1.program["helper/1"], "helper must be pulled cross-file")
        assertTrue(model1.program["helper/1"]!!.isComplete, "helper lowers cleanly: ${model1.program["helper/1"]!!.diagnostics}")
        assertTrue(builds1.get() > 0, "the cold session must actually lower")
        cache1.flush()

        // Session 2: a fresh instance over the SAME cache/salt — everything decodes, NO resolver is built.
        val builds2 = AtomicInteger()
        val model2 = lowering(dir, cacheDir, "salt", builds2).crossFileModel(parsedEntry(dir))
        assertNotNull(model2.program["helper/1"], "helper must still be pulled after restart")
        assertNotNull(model2.program["entry/0"])
        assertEquals(0, builds2.get(), "a restart over an unchanged project must decode, never re-lower")
    }

    @Test
    fun staleSaltAndEditedDeclarationsMiss() {
        val dir = project()
        val cacheDir = Files.createTempDirectory("plc-test")
        val cache = PreviewLoweringDiskCache(cacheDir, "salt")
        KotlinPreviewLowering(
            KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath())), diskCache = cache,
        ).crossFileModel(parsedEntry(dir))
        cache.flush()

        // A classpath change (different salt) must MISS — the fresh session re-lowers (resolvers are built).
        val saltBuilds = AtomicInteger()
        lowering(dir, cacheDir, "other-salt", saltBuilds).crossFileModel(parsedEntry(dir))
        assertTrue(saltBuilds.get() > 0, "a salt mismatch must not serve stale entries")

        // A SIGNATURE edit to the helper file must invalidate its stored entry (sigHash gate) — the helper
        // file re-lowers — while the untouched entry file still decodes with no resolver of its own. (The
        // signature-elided hash is what gates a file's entry, so an edit that changes a signature moves it.)
        Files.writeString(
            dir.resolve("Helper.kt"),
            helperText.replace("fun helper(s: String)", "fun helper(s: String, n: Int = 0)"),
        )
        val editBuilds = AtomicInteger()
        val model = lowering(dir, cacheDir, "salt", editBuilds).crossFileModel(parsedEntry(dir))
        assertNotNull(model.program["helper/2"], "the edited helper (new arity) must be pulled fresh")
        assertTrue(editBuilds.get() > 0, "an edited file must re-lower, not serve the stale tree")
    }

    @Test
    fun loadRejectsCorruptEntries() {
        val dir = project()
        val cacheDir = Files.createTempDirectory("plc-test")
        val cache = PreviewLoweringDiskCache(cacheDir, "salt")
        KotlinPreviewLowering(
            KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath())), diskCache = cache,
        ).crossFileModel(parsedEntry(dir))
        cache.flush()
        val files = Files.list(cacheDir).use { s -> s.filter { it.toString().endsWith(".plc") }.toList() }
        assertTrue(files.isNotEmpty(), "the lowering must have persisted entries")
        files.forEach { Files.write(it, ByteArray(7) { b -> b.toByte() }) }
        val reader = PreviewLoweringDiskCache(cacheDir, "salt")
        assertNull(reader.load(dir.resolve("Entry.kt").toString()), "corrupt entries must miss")
        assertNull(reader.load(dir.resolve("Helper.kt").toString()), "corrupt entries must miss")
    }
}
