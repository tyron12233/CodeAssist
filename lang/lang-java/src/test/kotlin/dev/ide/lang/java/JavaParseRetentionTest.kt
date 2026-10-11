package dev.ide.lang.java

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaIncrementalParser
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.psi.IntellijPsiHost
import dev.ide.testkit.TestDocument
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * A superseded live parse must become collectable. Each edit parses a new non-physical file, which IntelliJ's
 * `ResolveCache` never hears about; its soft-valued entries point back into the tree they resolved in, so
 * without a clear every old tree stayed reachable and typing in a Java file filled the heap.
 */
class JavaParseRetentionTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-retention").toFile()
        File(srcRoot, "com/foo").mkdirs()
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private class Snap(file: VirtualFile, text: CharSequence) : DocumentSnapshot by TestDocument(text, file, 1)

    private fun source(edit: Int) = """
        package com.foo;
        public class Notes {
            private final java.util.List<String> items = new java.util.ArrayList<>();
            int count(String prefix) {
                int n = 0;
                for (String s : items) if (s.startsWith(prefix)) n += s.length();
                String last = items.isEmpty() ? prefix : items.get(items.size() - 1);
                return n + last.length() + $edit;
            }
        }
    """.trimIndent()

    /** What a highlighting pass does to a tree: resolve every reference in it, through `ResolveCache`. */
    private fun resolveAll(file: PsiJavaFile) = IntellijPsiHost.withParseLock {
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                element.references.forEach { it.resolve() }
                super.visitElement(element)
            }
        })
    }

    /** Parse and resolve one version, holding the tree only weakly so the caller's frame can't pin it. */
    private fun parseAndResolve(parser: JavaIncrementalParser, vf: VirtualFile, edit: Int): WeakReference<PsiJavaFile> {
        val tree = (parser.parseFull(Snap(vf, source(edit))) as JavaParsedFile).javaFile
        resolveAll(tree)
        return WeakReference(tree)
    }

    @Test
    fun anEditedFilesOldTreeIsCollectable() {
        val file = File(srcRoot, "com/foo/Notes.java").apply { writeText(source(0)) }
        val vf = LocalFileSystem(srcRoot.toPath()).fileFor(file.toPath())
        val parser = JavaIncrementalParser(env)

        val firstRef = parseAndResolve(parser, vf, 0)
        for (edit in 1..20) parseAndResolve(parser, vf, edit)

        repeat(5) { System.gc(); Thread.sleep(50) }
        assertNull(firstRef.get(), "the tree from before 20 edits is still reachable")
    }
}
