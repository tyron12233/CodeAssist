package dev.ide.android.support.tools

import dev.ide.build.engine.RunDexRequest
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RunDexer] (the console-run dex backend) must dex an immutable library jar ONCE and reuse it — both within
 * a project (an edit re-dexes only the user classes) and across projects/cleans (via the shared cache). This
 * is the Phase-1 fix for the "every `.kt` edit re-dexes kotlin-stdlib + every dependency" cost. A fake
 * [Dexer] that only counts invocations and writes a stub `.dex` exercises the caching offline (no real D8).
 */
class RunDexerTest {

    /** Records each dex call's first input filename (so we can tell a library dex from the user-class dex) and
     *  writes a stub `classes.dex` so `DexArchives.hasDex(outDir)` is satisfied afterwards. */
    private class CountingDexer : Dexer {
        val dexedInputs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            dexedInputs.add(inputs.firstOrNull()?.fileName?.toString() ?: "?")
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1))
            return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult =
            ToolResult.fail("RunDexer must use indexed dex(), not dexArchive()")
        fun libCalls() = dexedInputs.count { it.startsWith("staged-") }
        fun userCalls() = dexedInputs.count { it == "user.jar" }
    }

    /** A minimal, valid `.class` — RunDexer reads every class through ASM (strip-metadata), so stub bytes won't do. */
    private fun trivialClass(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun librariesAreDexedOnceAndReusedAcrossBuildsAndProjects() {
        val tmp = Files.createTempDirectory("rundex")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            val libJar = libs.resolve("foo.jar")
            JarOutputStream(Files.newOutputStream(libJar)).use { jos ->
                jos.putNextEntry(JarEntry("demo/Lib.class")); jos.write(trivialClass("demo/Lib")); jos.closeEntry()
            }
            val userRoot = tmp.resolve("classes")
            Files.createDirectories(userRoot.resolve("demo"))
            Files.write(userRoot.resolve("demo/Main.class"), trivialClass("demo/Main"))

            val cache = tmp.resolve("cache")
            val dexer = CountingDexer()
            val backend = RunDexer(dexer, tmp.resolve("absent-android.jar"), cache)

            fun req(stage: String, out: String) = RunDexRequest(
                userClassDirs = listOf(userRoot),
                libJars = listOf(libJar),
                minApi = 26,
                instrument = { _, b -> b },
                guardVersion = "1.1",
                stagingDir = tmp.resolve(stage),
                outDex = tmp.resolve(out),
            )

            // Build 1 (cold): library + user classes both dexed; library seeds the shared cache.
            val r1 = backend.dexForRun(req("stage1", "out1"))
            assertTrue(r1.success, "build 1 failed: ${r1.log}")
            assertEquals(1, dexer.libCalls(), "cold build dexes the library once")
            assertEquals(1, dexer.userCalls(), "cold build dexes the user classes")
            assertTrue(Files.exists(tmp.resolve("out1/classes.dex")), "build 1 produced no dex")

            // Build 2 (same staging — an edit): library reused from its local bucket, only user re-dexed.
            val r2 = backend.dexForRun(req("stage1", "out2"))
            assertTrue(r2.success, "build 2 failed: ${r2.log}")
            assertEquals(1, dexer.libCalls(), "an edit must NOT re-dex the library")
            assertEquals(2, dexer.userCalls(), "an edit re-dexes only the user classes")

            // Build 3 (fresh staging = another project / after a clean, same shared cache): library copied
            // from the shared cache, still no re-dex.
            val r3 = backend.dexForRun(req("stage2", "out3"))
            assertTrue(r3.success, "build 3 failed: ${r3.log}")
            assertEquals(1, dexer.libCalls(), "the shared cache serves the library across projects; no re-dex")
            assertTrue(r3.log.any { it.contains("shared cache") }, "build 3 should reuse from the shared cache: ${r3.log}")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
