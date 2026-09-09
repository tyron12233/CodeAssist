package dev.ide.lang.java

import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.vfs.local.LocalFileSystem
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `@Override`-on-a-non-override check against a COMPILED (decompiled `Cls`) library hierarchy — the real
 * on-device report: `MainActivity extends AppCompatActivity` (from an AAR) with `@Override onCreate` flagged
 * "does not override or implement". The demo (`extends android.app.Activity`, whole chain present) can't
 * reproduce it because the hierarchy resolves fully. This uses a two-jar chain (`Mid extends Base`, `onCreate`
 * declared on `Base`) and drops `Base` from the classpath to mimic a missing transitive dependency — the
 * decisive question being whether the guard's supertype walk detects a break BEHIND a library class.
 */
class JavaLibraryOverrideTest {
    private val fs = LocalFileSystem(Files.createTempDirectory("java-libov-fs"))
    private lateinit var baseDir: File   // holds lib/Base.class
    private lateinit var midDir: File    // holds lib/Mid.class (extends lib/Base)
    private lateinit var srcRoot: File
    private val envs = mutableListOf<JavaEnvironment>()

    @AfterTest
    fun tearDown() {
        envs.forEach { it.close() }
        listOf(baseDir, midDir, srcRoot).forEach { it.deleteRecursively() }
    }

    init {
        baseDir = Files.createTempDirectory("java-base").toFile()
        midDir = Files.createTempDirectory("java-mid").toFile()
        srcRoot = Files.createTempDirectory("java-libov-src").toFile()
        File(baseDir, "lib").mkdirs(); File(midDir, "lib").mkdirs()
        // public class lib.Base { public void onCreate() {} }
        File(baseDir, "lib/Base.class").writeBytes(classBytes("lib/Base", "java/lang/Object", withOnCreate = true))
        // public class lib.Mid extends lib.Base {}
        File(midDir, "lib/Mid.class").writeBytes(classBytes("lib/Mid", "lib/Base", withOnCreate = false))
    }

    private fun classBytes(internalName: String, superName: String, withOnCreate: Boolean): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, superName, null)
        if (withOnCreate) {
            cw.visitMethod(Opcodes.ACC_PUBLIC, "onCreate", "()V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun overrideDiags(classpath: List<File>, src: String): List<String> {
        val env = JavaEnvironment.create(classpath, listOf(srcRoot), File(System.getProperty("java.home")))
        envs += env
        val f = File(srcRoot, "com/foo/X.java").apply { parentFile.mkdirs(); writeText(src) }
        return JavaParsedFile(env.parse("com/foo/X.java", src), fs.fileFor(f.toPath()), 1L)
            .diagnostics.filter { it.code == JavaDiagnosticCodes.INVALID_OVERRIDE }.map { it.message }
    }

    private val overridesOnCreate =
        "package com.foo;\npublic class X extends lib.Mid { @Override public void onCreate() {} }"

    @Test
    fun overrideThroughACompleteLibraryChainIsNotFlagged() {
        // Both jars present: X -> Mid -> Base.onCreate resolves; findSuperMethods must see it (Cls transitivity).
        val d = overrideDiags(listOf(baseDir, midDir), overridesOnCreate)
        assertFalse(d.any { "does not override or implement" in it },
            "a real override inherited through a library chain must not be flagged; got $d")
    }

    @Test
    fun overrideWithAMissingLibraryAncestorIsNotFlagged() {
        // Base DROPPED (a missing transitive dep): Mid resolves, but its super Base doesn't. The guard must
        // detect the break BEHIND the library class Mid and back off — the exact AppCompatActivity case.
        val d = overrideDiags(listOf(midDir), overridesOnCreate)
        assertFalse(d.any { "does not override or implement" in it },
            "an @Override whose declaring ancestor is off the classpath must not be flagged; got $d")
    }

    @Test
    fun overrideWhoseSignatureFindSuperMethodsCannotMatchIsNotFlagged() {
        // `Base.onCreate()` takes no args; this override takes an int, so `findSuperMethods()` returns empty even
        // though the whole hierarchy resolves. This is the desktop-reproducible proxy for the reported on-device
        // case (findSuperMethods empty for a genuine override against a resolvable library class): the name scan
        // sees `Base.onCreate` and backs off, so valid code is never flagged "does not override".
        val d = overrideDiags(listOf(baseDir, midDir),
            "package com.foo;\npublic class X extends lib.Mid { @Override public void onCreate(int extra) {} }")
        assertFalse(d.any { "does not override or implement" in it },
            "an @Override whose name exists in a supertype must not be flagged even when findSuperMethods can't match it; got $d")
    }

    @Test
    fun genuineNonOverrideInACompleteLibraryChainIsStillFlagged() {
        // Negative control: bogus() overrides nothing and the whole hierarchy resolves → the check must fire.
        val d = overrideDiags(listOf(baseDir, midDir),
            "package com.foo;\npublic class X extends lib.Mid { @Override public void bogus() {} }")
        assertTrue(d.any { "does not override or implement" in it },
            "a genuine non-override in a complete library hierarchy must still be flagged; got $d")
    }
}
