package dev.ide.lang.jdt

import dev.ide.index.AnnotationIndex
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.SubtypeIndex
import dev.ide.lang.jdt.index.JavaSourceAnnotationIndex
import dev.ide.lang.jdt.index.JavaSourceSubtypeIndex
import dev.ide.platform.ContentHash
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The JAVA-SOURCE direct-inheritor and annotated-by producers: SHORT-name keys from the binding-free
 * relations parse, best-effort import resolution in the values, nested types keyed by their full path.
 */
class JavaRelationIndexesTest {

    private fun input(text: String) = object : IndexInput {
        override val origin = IndexOrigin.SOURCE
        override val contentHash = ContentHash("")
        override val unitName = "T.java"
        override val sourcePath: Path = Paths.get("/virtual/T.java")
        override fun bytes() = text.toByteArray()
        override fun text() = text
        override fun dom() = null
    }

    @Test
    fun capturesExtendsAndImplementsWithImportResolution() {
        val out = JavaSourceSubtypeIndex.index(
            input(
                """
                package app;
                import lib.Base;
                public class Impl extends Base implements Runnable {
                    static class Nested implements java.io.Serializable {}
                }
                """.trimIndent(),
            ),
        )
        val base = out[SubtypeIndex.key("Base")].orEmpty()
        assertTrue(base.any { it.fqn == "app.Impl" && it.supertype == "lib.Base" }, "extends via import; got $base")
        assertTrue(out[SubtypeIndex.key("Runnable")].orEmpty().any { it.fqn == "app.Impl" }, "implements; got ${out.keys}")
        assertTrue(
            out[SubtypeIndex.key("Serializable")].orEmpty().any { it.fqn == "app.Impl.Nested" && it.supertype == "java.io.Serializable" },
            "nested type keyed by full path + dotted ref kept as FQN; got ${out.keys}",
        )
    }

    @Test
    fun capturesTypeAndMemberAnnotations() {
        val out = JavaSourceAnnotationIndex.index(
            input(
                """
                package app;
                import org.junit.Test;
                @Deprecated
                public class Suite {
                    @Test public void runsTheThing() {}
                    @Deprecated public int flag;
                }
                """.trimIndent(),
            ),
        )
        assertTrue(
            out[AnnotationIndex.key("Deprecated")].orEmpty().map { it.fqn }.containsAll(listOf("app.Suite", "app.Suite#flag")),
            "class + field annotations; got ${out.values.flatten()}",
        )
        val tests = out[AnnotationIndex.key("Test")].orEmpty()
        assertTrue(
            tests.any { it.fqn == "app.Suite#runsTheThing" && it.annotation == "org.junit.Test" },
            "member annotation with import-resolved FQN; got $tests",
        )
    }
}
