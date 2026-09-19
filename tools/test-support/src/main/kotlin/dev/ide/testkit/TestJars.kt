package dev.ide.testkit

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    /**
     * A jar of the running JDK's `java.base` classes, for analysis harnesses that need a JDK on the classpath.
     *
     * The symbol layer reads jars and class directories. Since Java 9 the platform classes are neither: they
     * live in the runtime image, off `java.class.path` entirely. So a harness assembled from the test
     * classpath has no `java.lang.StringBuilder` and no `java.util.Map`, and every JDK member resolves to
     * nothing -- which reads as a checker bug and is not one. The classes are still reachable through the
     * `jrt:` filesystem, so this packs them into the one shape the reader does understand.
     *
     * Cached at [cacheDir] keyed by `java.version`, since walking and repacking ~6,000 entries is not
     * something a test should do twice. Null if the runtime image cannot be opened, so a caller on some
     * exotic runtime self-gates rather than failing.
     */
    fun jdkBaseJar(cacheDir: Path): Path? = runCatching {
        val dest = cacheDir.resolve("java.base-${System.getProperty("java.version")}.jar")
        if (Files.isRegularFile(dest)) return@runCatching dest
        Files.createDirectories(cacheDir)
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        val root = jrt.getPath("/modules/java.base")
        val tmp = Files.createTempFile(cacheDir, "java.base", ".jar")
        JarOutputStream(Files.newOutputStream(tmp)).use { jos ->
            Files.walk(root).use { paths ->
                for (p in paths) {
                    if (!p.toString().endsWith(".class")) continue
                    jos.putNextEntry(JarEntry(root.relativize(p).toString()))
                    jos.write(Files.readAllBytes(p))
                    jos.closeEntry()
                }
            }
        }
        // Atomic publish: a half-written jar left by a killed run would poison every later one.
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        dest
    }.getOrNull()

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
