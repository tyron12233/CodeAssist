package dev.ide.core

import dev.ide.lang.JvmIndexScopeProvider
import dev.ide.lang.java.JavaSourceAnalyzer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live-parse half of the Kotlin editor's parameter-name recovery. `java.sourceDoc` indexes
 * `LIBRARY_SOURCE` only, so a PROJECT `.java` class is never in it. Once a sibling module's compiled output
 * puts that class on the Kotlin module's classpath, this is the only thing standing between the call site and
 * `p0`/`p1`.
 */
class AnalyzerSourceDocsTest {

    private val root = createTempDirectory("analyzer-source-docs")

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    /** A stand-in for the module's `.java` analyzer, exposing roots the way every JVM backend does. */
    private class Roots(
        override var sourceRootPaths: List<Path> = emptyList(),
        override var librarySourceArchives: List<Path> = emptyList(),
    ) : JvmIndexScopeProvider {
        override val classpathJarPaths: List<Path> = emptyList()
        override val jdkHome: Path? = null
    }

    private fun javaSourceDir(): Path {
        val pkg = Files.createDirectories(root.resolve("src/com/example"))
        pkg.resolve("Widget.java").writeText(
            """
            package com.example;
            public class Widget {
                /** Sets the padding. */
                public void setPadding(int left, int top, int right, int bottom) {}
            }
            """.trimIndent()
        )
        return root.resolve("src")
    }

    @Test
    fun namesAProjectJavaMethodTheSourceDocIndexNeverSees() {
        val docs = AnalyzerSourceDocs(Roots(sourceRootPaths = listOf(javaSourceDir())))
        val m = assertNotNull(docs.method("com.example.Widget", "setPadding", 4))
        assertEquals(listOf("left", "top", "right", "bottom"), m.names)
        assertTrue(m.doc!!.contains("Sets the padding"), "javadoc should come through too: ${m.doc}")
    }

    @Test
    fun returnsNullForATypeWithNoSource() {
        val docs = AnalyzerSourceDocs(Roots(sourceRootPaths = listOf(javaSourceDir())))
        assertNull(docs.method("com.example.Missing", "whatever", 0))
    }

    /** The host attaches archives AFTER the analyzer is built (the SDK Manager's source download, the JDK
     *  `src.zip`), so a resolver captured once at wiring time would never see them. */
    @Test
    fun picksUpSourceRootsAttachedAfterConstruction() {
        val roots = Roots()
        val docs = AnalyzerSourceDocs(roots)
        assertNull(docs.method("com.example.Widget", "setPadding", 4), "nothing is attached yet")

        roots.sourceRootPaths = listOf(javaSourceDir())
        val m = assertNotNull(docs.method("com.example.Widget", "setPadding", 4), "the new root must be seen")
        assertEquals(listOf("left", "top", "right", "bottom"), m.names)
    }

    /**
     * The regression this file exists for: the wiring used to cast to the concrete `JdtSourceAnalyzer`, and
     * when the IntelliJ-PSI backend took over `.java` the cast started yielding null, degrading SILENTLY to
     * "no parameter names" instead of failing. Keying on the neutral capability is what makes a future backend
     * swap a compile error rather than a quiet loss of names.
     */
    @Test
    fun theJavaEditorBackendSatisfiesTheCapabilityTheWiringKeysOn() {
        assertTrue(
            JvmIndexScopeProvider::class.java.isAssignableFrom(JavaSourceAnalyzer::class.java),
            "AnalyzerSourceDocs is wired off JvmIndexScopeProvider; the .java analyzer must implement it",
        )
    }
}
