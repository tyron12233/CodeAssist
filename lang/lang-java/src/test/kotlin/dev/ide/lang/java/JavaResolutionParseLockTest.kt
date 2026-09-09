package dev.ide.lang.java

import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.psi.IntellijPsiHost
import dev.ide.testkit.TestDocument
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ART-safety pin: every Java editor path that RESOLVES (`resolve()`/`getType()`/`facade.findClass`) must hold
 * the ONE global parse lock, because resolution can lazily parse (`buildTree`) a referenced source file — and
 * two concurrent `buildTree`s corrupt PSI's internals on 32-bit ART (a native SIGSEGV; issues #1396/#1332).
 * The parse itself was already serialized; the remaining hole was the RESOLUTION that runs *after* the parse
 * (completion's member access, go-to-def, hover). The injected element finder consults the overlay on every
 * `findClass`, so this probes the lock state from that callback and fails if a resolving path forgot the lock.
 */
class JavaResolutionParseLockTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-lock").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Greeter.java").writeText(
            """
            package com.foo;
            public class Greeter {
                public String greet(String who) { return who; }
            }
            """.trimIndent(),
        )
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env)
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private class Snap(file: VirtualFile, text: CharSequence) : DocumentSnapshot by TestDocument(text, file, 1)

    @Test
    fun completionResolutionHoldsTheParseLock() {
        val consulted = AtomicBoolean(false)
        val everUnlocked = AtomicBoolean(false)
        // The injected finder calls overlay() on EVERY findClass; record whether the lock is held each time.
        env.overlayProvider = {
            consulted.set(true)
            if (!IntellijPsiHost.isParseLockHeldByCurrentThread()) everUnlocked.set(true)
            emptyMap()
        }

        // Member access resolves the receiver's type (`new Greeter()` → findClass(Greeter)) → the finder.
        val source = "package com.foo;\nclass Use { void m() { new Greeter().| } }"
        val offset = source.indexOf('|')
        val text = source.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        runBlocking {
            analyzer.complete(
                CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit),
                JavaLanguageBackend.LANGUAGE_ID,
            )
        }

        assertTrue(consulted.get(), "completion must resolve through the element finder — else this probe proves nothing")
        assertFalse(everUnlocked.get(), "Java completion resolution must hold the parse lock (no concurrent buildTree on ART)")
    }

    @Test
    fun goToDefinitionResolutionHoldsTheParseLock() {
        val everUnlocked = AtomicBoolean(false)
        val consulted = AtomicBoolean(false)
        env.overlayProvider = {
            consulted.set(true)
            if (!IntellijPsiHost.isParseLockHeldByCurrentThread()) everUnlocked.set(true)
            emptyMap()
        }
        // scopeAt(...).resolve(name) → facade.findClass(name) → the finder (deterministic).
        val vf = fs.fileFor(File(srcRoot, "com/foo/Greeter.java").toPath())
        val scope = analyzer.scopeAt(vf, 0)
        scope.resolve("Greeter")

        assertTrue(consulted.get(), "scope resolution must reach findClass → the element finder")
        assertFalse(everUnlocked.get(), "Java scope resolution must hold the parse lock")
    }
}
