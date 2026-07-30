package dev.ide.testkit

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/** Locates jars on the test classpath and builds throwaway jars for tests. */
object TestJars {
    /** The kotlin-stdlib jar on the test classpath (the one carrying `kotlin/Pair.class`). */
    fun kotlinStdlib(): Path = onClasspath("kotlin/Pair.class")

    /** The first classpath jar containing the zip entry [entry] (e.g. `kotlin/Pair.class`). */
    fun onClasspath(entry: String): Path = containing(entry)

    /** The first classpath jar containing ALL of [entries]. */
    fun containing(vararg entries: String): Path {
        val cp = System.getProperty("java.class.path").split(File.pathSeparator)
        val hit = cp.firstOrNull { e ->
            e.endsWith(".jar") && runCatching {
                ZipFile(e).use { zf -> entries.all { zf.getEntry(it) != null } }
            }.getOrDefault(false)
        } ?: error("no classpath jar contains all of ${entries.toList()}")
        return Path.of(hit)
    }

    /** Build a jar at [dest] (a fresh temp file by default) from the entries added in [build]. */
    fun buildJar(dest: Path = Files.createTempFile("testkit", ".jar"), build: JarBuilder.() -> Unit): Path {
        val builder = JarBuilder().apply(build)
        JarOutputStream(Files.newOutputStream(dest)).use { jos ->
            for ((path, bytes) in builder.entries) {
                jos.putNextEntry(JarEntry(path))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return dest
    }
}

/** Accumulates entries for [TestJars.buildJar]. */
class JarBuilder {
    val entries: LinkedHashMap<String, ByteArray> = LinkedHashMap()

    /** Add a raw entry at [path] (verbatim bytes). */
    fun entry(path: String, bytes: ByteArray) {
        entries[path] = bytes
    }

    /** Add a `.class` entry for [internalName] (e.g. `com/example/Foo`) from its compiled [bytes]. */
    fun clazz(internalName: String, bytes: ByteArray) {
        entries["$internalName.class"] = bytes
    }

    /**
     * Generate + add a class [internalName] extending [superName] (and optional [interfaces]). Use
     * [customize] to add members with the ASM [ClassWriter]. Handy when a test only needs a class to *exist*
     * on a classpath (e.g. a `View` subclass scan) or to carry a specific shape.
     */
    fun asmClass(
        internalName: String,
        superName: String = "java/lang/Object",
        access: Int = Opcodes.ACC_PUBLIC,
        interfaces: Array<String>? = null,
        version: Int = Opcodes.V1_8,
        customize: ClassWriter.() -> Unit = {},
    ) {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(version, access, internalName, null, superName, interfaces)
        cw.customize()
        cw.visitEnd()
        clazz(internalName, cw.toByteArray())
    }
}
