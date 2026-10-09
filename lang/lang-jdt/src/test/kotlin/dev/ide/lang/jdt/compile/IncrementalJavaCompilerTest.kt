package dev.ide.lang.jdt.compile

import dev.ide.lang.jdt.compile.IncrementalJavaCompiler.Mode
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which edits recompile only the edited file, which fall back to compiling the module, and that the output
 * always matches what a full compile of the current sources would hold.
 */
class IncrementalJavaCompilerTest {

    private class Module(val src: Path, val out: Path, val classpath: List<Path> = emptyList(), val boot: List<Path> = emptyList()) {
        fun write(rel: String, text: String): Path = src.resolve(rel).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }

        fun sources(): List<Path> = Files.walk(src).use { s -> s.filter { it.toString().endsWith(".java") }.toList() }

        fun compile(): IncrementalJavaCompiler.Result =
            IncrementalJavaCompiler().compile(sources(), classpath, out, "17", boot)

        fun classBytes(rel: String): ByteArray = Files.readAllBytes(out.resolve(rel))
    }

    private fun module(dir: Path, name: String = "m", classpath: List<Path> = emptyList(), boot: List<Path> = emptyList()) =
        Module(dir.resolve("$name/src"), dir.resolve("$name/classes"), classpath, boot)

    private fun Module.greeterAndUser() {
        write("p/Greeter.java", "package p; public class Greeter { public String greet() { return \"hello\"; } }")
        write("p/User.java", "package p; public class User { public String run() { return new Greeter().greet(); } }")
    }

    private fun IncrementalJavaCompiler.Result.assertOk(mode: Mode) {
        assertTrue(success, "compile failed: $messages")
        assertEquals(mode, this.mode, "mode (recompiled ${recompiledSources.map { it.fileName }})")
    }

    @Test
    fun anUnchangedModuleIsNotRecompiled() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            m.compile().assertOk(Mode.NOOP)
        }
    }

    @Test
    fun aBodyEditRecompilesOnlyThatFile() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            val userBefore = m.classBytes("p/User.class")
            val userTime = Files.getLastModifiedTime(m.out.resolve("p/User.class"))

            m.write("p/Greeter.java", "package p; public class Greeter { public String greet() { return \"changed\"; } }")
            val r = m.compile()
            r.assertOk(Mode.INCREMENTAL)
            assertEquals(listOf("Greeter.java"), r.recompiledSources.map { it.fileName.toString() })
            assertTrue(String(m.classBytes("p/Greeter.class"), Charsets.ISO_8859_1).contains("changed"))
            assertContentEquals(userBefore, m.classBytes("p/User.class"))
            assertEquals(userTime, Files.getLastModifiedTime(m.out.resolve("p/User.class")))
        }
    }

    @Test
    fun privateMembersAndLocalClassesStayIncremental() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            m.write(
                "p/Greeter.java",
                """
                package p;
                public class Greeter {
                    private int calls;
                    private static String helper() { return "x"; }
                    public String greet() {
                        Runnable r = new Runnable() { public void run() { calls++; } };
                        r.run();
                        return helper();
                    }
                }
                """.trimIndent(),
            )
            m.compile().assertOk(Mode.INCREMENTAL)
            assertTrue(Files.exists(m.out.resolve("p/Greeter$1.class")))

            // Dropping the anonymous class again removes its class file without a full compile.
            m.write("p/Greeter.java", "package p; public class Greeter { public String greet() { return \"plain\"; } }")
            m.compile().assertOk(Mode.INCREMENTAL)
            assertFalse(Files.exists(m.out.resolve("p/Greeter$1.class")))
        }
    }

    @Test
    fun aSignatureChangeCompilesTheModule() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            m.write(
                "p/Greeter.java",
                "package p; public class Greeter { public String greet() { return \"a\"; } public void extra() {} }",
            )
            m.compile().assertOk(Mode.FULL)
        }
    }

    @Test
    fun aRemovedMethodIsReportedInItsCallers() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            m.write("p/Greeter.java", "package p; public class Greeter { }")
            val r = m.compile()
            assertFalse(r.success, "User calls the removed greet()")
            assertTrue(r.diagnostics.any { it.file.orEmpty().endsWith("User.java") }, "error in the caller: ${r.diagnostics}")
        }
    }

    @Test
    fun aChangedConstantRecompilesTheClassesThatInlinedIt() {
        withTempDir("ijc") { dir ->
            val m = module(dir)
            m.write("p/Limits.java", "package p; public class Limits { public static final int MAX = 1111; }")
            m.write("p/Check.java", "package p; public class Check { public int max() { return Limits.MAX; } }")
            m.compile().assertOk(Mode.FULL)
            m.write("p/Limits.java", "package p; public class Limits { public static final int MAX = 2222; }")
            m.compile().assertOk(Mode.FULL)
            val check = m.classBytes("p/Check.class")
            // 2222 = 0x08AE, loaded with sipush.
            assertTrue(check.toList().windowed(3).any { it == listOf(0x11.toByte(), 0x08.toByte(), 0xAE.toByte()) })
        }
    }

    @Test
    fun aRemovedSourceTakesItsClassesWithIt() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            val extra = m.write("p/Extra.java", "package p; class Extra { class Inner {} }")
            m.compile().assertOk(Mode.FULL)
            Files.delete(extra)
            m.compile().assertOk(Mode.FULL)
            assertFalse(Files.exists(m.out.resolve("p/Extra.class")))
            assertFalse(Files.exists(m.out.resolve("p/Extra\$Inner.class")))
        }
    }

    @Test
    fun aNewUnreferencedSourceIsCompiledAlone() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            m.write("p/Fresh.java", "package p; public class Fresh { public int one() { return 1; } }")
            val r = m.compile()
            r.assertOk(Mode.INCREMENTAL)
            assertEquals(listOf("Fresh.java"), r.recompiledSources.map { it.fileName.toString() })
            assertTrue(Files.exists(m.out.resolve("p/Fresh.class")))
        }
    }

    @Test
    fun aNewClassThatCanShadowAnImportCompilesTheModule() {
        withTempDir("ijc") { dir ->
            val m = module(dir)
            m.write("p/Uses.java", "package p; import java.util.*; public class Uses { public List<String> xs() { return new ArrayList<>(); } }")
            m.compile().assertOk(Mode.FULL)
            // A same-package `List` now wins over `java.util.*`, so Uses has to be compiled again.
            m.write("p/List.java", "package p; public class List<T> {}")
            val r = m.compile()
            assertEquals(Mode.FULL, r.mode)
            assertFalse(r.success, "Uses now resolves List to p.List: ${r.messages}")
        }
    }

    @Test
    fun aCompileErrorKeepsTheLastGoodOutputAndIsRetried() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            val good = m.classBytes("p/Greeter.class")

            m.write("p/Greeter.java", "package p; public class Greeter { public String greet() { return 42; } }")
            val failed = m.compile()
            assertFalse(failed.success)
            assertEquals(Mode.INCREMENTAL, failed.mode)
            assertTrue(failed.diagnostics.any { it.isError && it.file.orEmpty().endsWith("Greeter.java") })
            assertContentEquals(good, m.classBytes("p/Greeter.class"))

            m.write("p/Greeter.java", "package p; public class Greeter { public String greet() { return \"fixed\"; } }")
            m.compile().assertOk(Mode.INCREMENTAL)
            assertTrue(String(m.classBytes("p/Greeter.class"), Charsets.ISO_8859_1).contains("fixed"))
        }
    }

    @Test
    fun aDeletedOutputClassForcesAFullCompile() {
        withTempDir("ijc") { dir ->
            val m = module(dir).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            Files.delete(m.out.resolve("p/User.class"))
            m.write("p/Greeter.java", "package p; public class Greeter { public String greet() { return \"b\"; } }")
            m.compile().assertOk(Mode.FULL)
            assertTrue(Files.exists(m.out.resolve("p/User.class")))
        }
    }

    @Test
    fun aDependencyBodyEditLeavesItsDependentAlone() {
        withTempDir("ijc") { dir ->
            val lib = module(dir, "lib")
            lib.write("lib/Api.java", "package lib; public class Api { public static String name() { return \"one\"; } }")
            val app = module(dir, "app", classpath = listOf(lib.out))
            app.write("app/Main.java", "package app; public class Main { public String n() { return lib.Api.name(); } }")
            lib.compile().assertOk(Mode.FULL)
            app.compile().assertOk(Mode.FULL)

            lib.write("lib/Api.java", "package lib; public class Api { public static String name() { return \"two\"; } }")
            lib.compile().assertOk(Mode.INCREMENTAL)
            app.compile().assertOk(Mode.NOOP)

            lib.write("lib/Api.java", "package lib; public class Api { public static String name(int x) { return \"three\"; } }")
            lib.compile().assertOk(Mode.FULL)
            val r = app.compile()
            assertEquals(Mode.FULL, r.mode)
            assertFalse(r.success, "Main calls the old name(): ${r.messages}")
        }
    }

    @Test
    fun theAndroidPathCompilesIncrementallyToo() {
        val androidJar = androidJar() ?: run { println("[IncrementalJavaCompiler] no Android SDK, skipping"); return }
        val stubs = coreLambdaStubs() ?: run { println("[IncrementalJavaCompiler] no core-lambda-stubs, skipping"); return }
        withTempDir("ijc") { dir ->
            val m = module(dir, boot = listOf(androidJar, stubs)).apply { greeterAndUser() }
            m.compile().assertOk(Mode.FULL)
            m.write("p/Greeter.java", "package p; public class Greeter { public String greet() { return \"android\" + 1; } }")
            m.compile().assertOk(Mode.INCREMENTAL)
            m.compile().assertOk(Mode.NOOP)
        }
    }

    private fun sdkRoots() = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
    ).map { Path.of(it) }.filter { Files.isDirectory(it) }

    private fun androidJar(): Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }

    private fun coreLambdaStubs(): Path? = sdkRoots().map { it.resolve("build-tools") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("core-lambda-stubs.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }
}
