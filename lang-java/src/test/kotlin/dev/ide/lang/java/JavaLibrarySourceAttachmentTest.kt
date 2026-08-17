package dev.ide.lang.java

import dev.ide.lang.JvmIndexScopeProvider
import dev.ide.testkit.TestJars
import dev.ide.testkit.compilationContext
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The backend must publish the library source attachments the project model declares
 * ([dev.ide.lang.CompilationContext.sourceAttachments]) on [JvmIndexScopeProvider.librarySourceArchives].
 *
 * That list is the ONLY route by which real parameter names reach the editor for a library: the host feeds it
 * both to `IndexScope.sourceArchives` (the `java.sourceDoc` index) and to the live-parse fallback behind the
 * Kotlin backend's `SourceDocProvider`. Java bytecode strips parameter names, so dropping the list doesn't
 * fail loudly — every library method just renders `p0`/`p1`, in `.java` and in `.kt` alike. This backend did
 * drop it: it only ever received the JDK `src.zip` and the Android framework sources that the host attaches by
 * hand, never the declared attachments, so a dependency WITH sources still completed as `p0`.
 */
class JavaLibrarySourceAttachmentTest {

    @Test
    fun declaredSourceAttachmentsArePublishedAsLibrarySourceArchives() = withTempDir("java-src-attach") { dir ->
        val srcRoot = Files.createDirectories(dir.resolve("src"))
        val sourcesDir = Files.createDirectories(dir.resolve("exploded-sources"))
        val sourcesJar = TestJars.buildJar(dir.resolve("gson-2.11.0-sources.jar")) {
            entry(
                "com/example/Api.java",
                """
                package com.example;
                public class Api {
                    /** Greets someone. */
                    public String greet(String who, int times) { return who; }
                }
                """.trimIndent().toByteArray(),
            )
        }

        val analyzer = JavaLanguageBackend().createAnalyzer(
            compilationContext(
                sourceRoots = listOf(srcRoot),
                sourceAttachments = listOf(sourcesJar, sourcesDir),
            )
        )

        val roots = (analyzer as JvmIndexScopeProvider).librarySourceArchives
        assertTrue(
            roots.containsAll(listOf(sourcesJar, sourcesDir)),
            "declared -sources.jar + exploded source dir must reach librarySourceArchives, " +
                "else library params render p0; got $roots",
        )
    }
}
