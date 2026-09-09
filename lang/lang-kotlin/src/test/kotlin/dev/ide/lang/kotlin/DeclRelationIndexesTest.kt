package dev.ide.lang.kotlin

import dev.ide.index.AnnotatedValue
import dev.ide.index.AnnotationIndex
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import dev.ide.lang.kotlin.index.BinaryAnnotationIndex
import dev.ide.lang.kotlin.index.BinarySubtypeIndex
import dev.ide.lang.kotlin.index.KotlinSourceAnnotationIndex
import dev.ide.lang.kotlin.index.KotlinSourceSubtypeIndex
import dev.ide.platform.ContentHash
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The direct-inheritor ([SubtypeIndex]) and annotated-by ([AnnotationIndex]) producers, binary + Kotlin
 * source sides: SHORT-name keys shared across the family, exact FQNs in binary values, best-effort
 * resolved references in source values, and the noise skips (`java.lang.Object`, `@kotlin.Metadata`).
 */
class DeclRelationIndexesTest {

    // ---- binary side (real stdlib bytecode) ----

    private fun stdlibClass(entry: String): ByteArray =
        ZipFile(stdlibJarPath().toFile()).use { z ->
            z.getInputStream(z.getEntry(entry)).use { it.readBytes() }
        }

    @Test
    fun binarySubtypesKeyByShortNameWithExactFqnInTheValue() {
        // kotlin.NotImplementedError extends java.lang.Error.
        val out = BinarySubtypeIndex.index(BinInput("kotlin/NotImplementedError.class", stdlibClass("kotlin/NotImplementedError.class")))
        val hits = out[SubtypeIndex.key("Error")].orEmpty()
        assertTrue(
            hits.any { it.fqn == "kotlin.NotImplementedError" && it.supertype == "java.lang.Error" },
            "expected NotImplementedError under key 'Error'; got ${out.keys}",
        )
    }

    @Test
    fun binarySubtypesSkipTheObjectBucket() {
        val out = BinarySubtypeIndex.index(BinInput("kotlin/Unit.class", stdlibClass("kotlin/Unit.class")))
        assertTrue(SubtypeIndex.key("Object") !in out, "java.lang.Object must not get a bucket; got ${out.keys}")
    }

    @Test
    fun binaryAnnotationsIndexClassAnnotationsAndSkipMetadata() {
        // kotlin.Deprecated is an annotation class annotated @Target/@Retention/@MustBeDocumented.
        val out = BinaryAnnotationIndex.index(BinInput("kotlin/Deprecated.class", stdlibClass("kotlin/Deprecated.class")))
        assertTrue(
            out[AnnotationIndex.key("Target")].orEmpty().any { it.fqn == "kotlin.Deprecated" },
            "@Target use on kotlin.Deprecated expected; got ${out.keys}",
        )
        assertTrue(AnnotationIndex.key("Metadata") !in out, "@kotlin.Metadata must be skipped; got ${out.keys}")
    }

    // ---- Kotlin source side ----

    private fun ktSubtypes(text: String): Map<String, Collection<SubtypeValue>> =
        KotlinSourceSubtypeIndex.index(SrcInput("S.kt", text))

    private fun ktAnnotations(text: String): Map<String, Collection<AnnotatedValue>> =
        KotlinSourceAnnotationIndex.index(SrcInput("S.kt", text))

    @Test
    fun kotlinSourceSubtypesCoverSealedHierarchiesAcrossNesting() {
        val out = ktSubtypes(
            """
            package demo
            sealed class Expr
            class Add : Expr()
            class Neg : Expr() { class Inner : Runnable }
            object Zero : Expr()
            """.trimIndent(),
        )
        val exprSubs = out[SubtypeIndex.key("Expr")].orEmpty().map { it.fqn }
        assertTrue(
            exprSubs.containsAll(listOf("demo.Add", "demo.Neg", "demo.Zero")),
            "all direct sealed subclasses expected; got $exprSubs",
        )
        assertTrue(
            out[SubtypeIndex.key("Runnable")].orEmpty().any { it.fqn == "demo.Neg.Inner" },
            "a NESTED class's supertype indexes too; got ${out.keys}",
        )
    }

    @Test
    fun kotlinSourceSubtypesResolveThroughImports() {
        val out = ktSubtypes("package demo\nimport other.Base\nclass Impl : Base()\n")
        assertTrue(
            out[SubtypeIndex.key("Base")].orEmpty().any { it.supertype == "other.Base" },
            "the imported supertype resolves to its FQN; got ${out.values.flatten()}",
        )
    }

    @Test
    fun kotlinSourceAnnotationsIndexClassesAndCallables() {
        val out = ktAnnotations(
            """
            package demo
            import androidx.compose.runtime.Composable
            @Deprecated("x") class Old
            @Composable fun Screen() {}
            class Host { @Composable fun Content() {} }
            """.trimIndent(),
        )
        assertTrue(out[AnnotationIndex.key("Deprecated")].orEmpty().any { it.fqn == "demo.Old" }, "class annotation; got ${out.keys}")
        val composables = out[AnnotationIndex.key("Composable")].orEmpty()
        assertTrue(composables.any { it.fqn == "demo.Screen" && it.kind == "function" }, "top-level fn; got $composables")
        assertTrue(composables.any { it.fqn == "demo.Host#Content" }, "member fn keyed owner#name; got $composables")
        assertTrue(
            composables.all { it.annotation == "androidx.compose.runtime.Composable" },
            "the import resolves the annotation FQN; got $composables",
        )
    }

    // ---- fakes ----

    private class BinInput(override val unitName: String, private val b: ByteArray) : IndexInput {
        override val origin = IndexOrigin.LIBRARY
        override val contentHash = ContentHash("")
        override val sourcePath: Path? = null
        override fun bytes() = b
        override fun text(): String? = null
        override fun dom() = null
    }

    private class SrcInput(override val unitName: String, private val text: String) : IndexInput {
        override val origin = IndexOrigin.SOURCE
        override val contentHash = ContentHash("")
        override val sourcePath: Path = Paths.get("/virtual/$unitName")
        override fun bytes() = text.toByteArray()
        override fun text() = text
        override fun dom() = null
    }
}
