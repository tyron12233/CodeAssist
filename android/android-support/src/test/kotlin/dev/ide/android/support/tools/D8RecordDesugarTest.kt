package dev.ide.android.support.tools

import dev.ide.android.support.assumeAndroidSdk
import dev.ide.testkit.withTempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desugaring a Java record needs a helper class that belongs to the whole program rather than to any one
 * input (the tag class standing in for `java.lang.Record`, which the platform only ships from API 34). The
 * per-class dex archive the build runs is an intermediate compilation, so D8 has nowhere to emit it unless it
 * is given a global-synthetics output, and refuses to compile the record at all without one: "Invalid build
 * configuration. Attempt to create a global synthetic for 'Record desugaring' without a global-synthetics
 * consumer." A project with a single record then failed to build.
 *
 * These pin the archive/merge contract: the archive writes the globals beside the class dex, and the merge
 * that finalizes the intermediates takes them back.
 */
class D8RecordDesugarTest {

    private val recordRel = "com/example/Point.class"

    /** `public record Point(int x)`, as bytecode: a final class extending `java.lang.Record` with a record
     *  component, its backing field, a canonical constructor and the component accessor. */
    private fun recordBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V16,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_RECORD,
            "com/example/Point", null, "java/lang/Record", null,
        )
        cw.visitRecordComponent("x", "I", null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "x", "I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "com/example/Point", "x", "I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "x", "()I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "com/example/Point", "x", "I")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun recordJar(dir: Path): Path {
        val jar = dir.resolve("record.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            jos.putNextEntry(JarEntry(recordRel)); jos.write(recordBytes()); jos.closeEntry()
        }
        return jar
    }

    @Test
    fun archivingARecordEmitsItsGlobalSyntheticsBesideTheClassDex() {
        withTempDir("d8-record-archive") { tmp ->
            val outDir = tmp.resolve("archive")

            val r = D8InProcessDexer().dexArchive(
                listOf(recordJar(tmp)), emptyList(), tmp.resolve("absent-android.jar"),
                minApi = 24, release = false, outDir = outDir, threads = 1,
            )

            assertTrue(r.success, "dexArchive of a record failed: ${r.log}")
            assertTrue(Files.isRegularFile(outDir.resolve("com/example/Point.dex")), "record produced no .dex: ${r.log}")
            assertTrue(
                Files.isRegularFile(outDir.resolve("com/example/Point.globals")),
                "record desugaring produced no global synthetics beside its dex: ${r.log}",
            )
        }
    }

    @Test
    fun mergingARecordArchiveFinalizesIt() {
        withTempDir("d8-record-merge") { tmp ->
            val androidJar = tmp.resolve("absent-android.jar")
            val archive = tmp.resolve("archive")
            val dexer = D8InProcessDexer()
            val archived = dexer.dexArchive(
                listOf(recordJar(tmp)), emptyList(), androidJar,
                minApi = 24, release = false, outDir = archive, threads = 1,
            )
            assertTrue(archived.success, "dexArchive of a record failed: ${archived.log}")

            val merged = tmp.resolve("dex")
            val r = dexer.dex(
                listOf(archive.resolve("com/example/Point.dex")), androidJar,
                minApi = 24, release = false, outDir = merged, threads = 1,
            )

            assertTrue(r.success, "merging the record archive failed: ${r.log}")
            assertTrue(Files.isRegularFile(merged.resolve("classes.dex")), "merge produced no classes.dex: ${r.log}")
        }
    }

    /** The desktop pipeline drives the same two steps through D8's command line, so the options it passes have
     *  to line up with the in-process calls above. Skipped when the machine's `d8.jar` predates R8 8, which has
     *  neither the options nor the split they exist for. */
    @Test
    fun theSubprocessD8ArchivesAndMergesARecordToo() {
        val sdk = assumeAndroidSdk()
        assumeTrue(
            DexGlobalSynthetics.supportedBy(listOf(sdk.d8Jar)),
            "installed build-tools d8.jar predates R8 8 (no global synthetics); skipping",
        )
        withTempDir("d8-record-subprocess") { tmp ->
            val dexer = D8Dexer(listOf(sdk.d8Jar), sdk.javaLauncher)
            val archive = tmp.resolve("archive")

            val archived = dexer.dexArchive(
                listOf(recordJar(tmp)), emptyList(), sdk.androidJar,
                minApi = 24, release = false, outDir = archive, threads = 1,
            )

            assertTrue(archived.success, "subprocess dexArchive of a record failed: ${archived.log}")
            assertTrue(
                Files.isRegularFile(archive.resolve("com/example/Point.globals")),
                "subprocess archive wrote no global synthetics: ${archived.log}",
            )

            val merged = tmp.resolve("dex")
            val r = dexer.dex(
                listOf(archive.resolve("com/example/Point.dex")), sdk.androidJar,
                minApi = 24, release = false, outDir = merged, threads = 1,
            )

            assertTrue(r.success, "subprocess merge of the record archive failed: ${r.log}")
            assertTrue(Files.isRegularFile(merged.resolve("classes.dex")), "merge produced no classes.dex: ${r.log}")
        }
    }
}
