package dev.ide.lang.jdt

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ecj's "The hierarchy of the type X is inconsistent" ([org.eclipse.jdt.core.compiler.IProblem.HierarchyHasProblems])
 * names only the broken subtype — never the ancestor actually missing from the classpath, which is what you
 * need to know which dependency to add. When the missing ancestor is a *binary* supertype's super (a class
 * present on the classpath whose own superclass was stripped), the editor now folds the missing FQN and who
 * required it into the message. See `JdtResolver.enrichHierarchyProblems`.
 */
class HierarchyDiagnosticsTest {

    @Test
    fun inconsistentHierarchyNamesTheMissingBinaryAncestor() {
        val javac = ToolProvider.getSystemJavaCompiler() ?: return // JRE-only environment → skip
        val tmp = Files.createTempDirectory("jdt-hier")
        try {
            // Compile lib.A + lib.B (B extends A), then jar ONLY B.class so A is absent from the classpath.
            val classes = tmp.resolve("classes").also { Files.createDirectories(it) }
            val srcA = tmp.resolve("lib/A.java").also { Files.createDirectories(it.parent) }
            Files.writeString(srcA, "package lib; public class A {}")
            val srcB = tmp.resolve("lib/B.java")
            Files.writeString(srcB, "package lib; public class B extends A {}")
            val rc = javac.run(null, null, null, "-d", classes.toString(), srcA.toString(), srcB.toString())
            assertTrue(rc == 0, "javac compile of the fixture failed (rc=$rc)")

            val jar = tmp.resolve("lib.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { jos ->
                jos.putNextEntry(JarEntry("lib/B.class"))
                jos.write(Files.readAllBytes(classes.resolve("lib/B.class")))
                jos.closeEntry()
            }

            val srcDir = tmp.resolve("src").also { Files.createDirectories(it.resolve("app")) }
            val analyzer = analyzerWithLibrary(listOf(srcDir), jar)
            val code = "package app; class C extends lib.B {}"
            val msgs = analyzer.diagnose(StubFile(srcDir.resolve("app/C.java").toString(), code), code).map { it.message }

            assertTrue(msgs.any { "inconsistent" in it && "lib.A" in it }, "missing ancestor must be named: $msgs")
            assertTrue(msgs.any { "required by lib.B" in it }, "the requiring supertype must be named: $msgs")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun analyzerWithLibrary(sourceDirs: List<Path>, libraryJar: Path): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = sourceDirs.map { StubFile(it.toString()) }
            override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = listOf(ClasspathEntry(StubFile(libraryJar.toString()), ClasspathEntryKind.LIBRARY))
                override fun fingerprint() = ContentHash(libraryJar.toString())
            }
            override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = listOf(ClasspathEntry(StubFile(System.getProperty("java.home")), ClasspathEntryKind.SDK_BOOTCLASSPATH))
                override fun fingerprint() = ContentHash("boot")
            }
            override val languageLevel = LanguageLevel.JAVA_17
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }
}
