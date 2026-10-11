package dev.ide.android.support.metadata.gen

import dev.ide.android.support.docs.AndroidPlatformDocs
import dev.ide.android.support.docs.AndroidPlatformDocs.ClassDocs
import dev.ide.android.support.docs.AndroidPlatformDocs.MethodDocs
import dev.ide.lang.java.index.JavaSourceDocs
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Generates the bundled Android docs pack (`android-docs.bin`, see [AndroidPlatformDocs]) from a platform's
 * framework sources: every class and method `android.jar` exposes, with its real parameter names and javadoc.
 * Hidden classes and members (in the sources, not in the jar) are left out.
 *
 * Usage:
 * ```
 * ./gradlew :android-sdk-metadata:generateAndroidDocs --args \
 *   "$SDK/sources/android-36 $SDK/platforms/android-36/android.jar android/android-support/src/main/resources/android-docs.bin 36"
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println("usage: <sources/android-N> <android.jar> <output.bin> <api>")
        return
    }
    val sources = Path.of(args[0])
    val jar = Path.of(args[1])
    val out = Path.of(args[2])
    val api = args[3].toInt()

    val classes = AndroidDocsExtractor.extract(sources, jar)
    val bytes = AndroidPlatformDocs.write(api, classes)
    out.parent?.let { Files.createDirectories(it) }
    Files.write(out, bytes)
    val methods = classes.sumOf { it.methods.size }
    val documented = classes.sumOf { c -> c.methods.count { it.doc != null } }
    println("Wrote ${out.toAbsolutePath()}: api=$api, ${classes.size} classes, $methods methods ($documented documented), ${bytes.size} bytes")
}

/** The sources-to-pack extraction, apart from file output so it can be tested. */
object AndroidDocsExtractor {

    /** The public surface of [jar]: class name (dotted, nested with `.`) -> its `name/arity` members. */
    fun publicSurface(jar: Path): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        ZipFile(jar.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val reader = ClassReader(zip.getInputStream(entry).use { it.readBytes() })
                val name = reader.className.replace('/', '.').replace('$', '.')
                val simple = name.substringAfterLast('.')
                val members = out.getOrPut(name) { HashSet() }
                reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(access: Int, n: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor? {
                        if (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return null
                        if (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
                        val arity = Type.getArgumentTypes(desc).size
                        if (n == "<init>") {
                            // An inner class's constructor takes its outer instance first, which the source does not name.
                            members += "$simple/$arity"; if (arity > 0) members += "$simple/${arity - 1}"
                        } else if (n != "<clinit>") members += "$n/$arity"
                        return null
                    }
                }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
        }
        return out
    }

    /** A javadoc block without its source indentation: ` * text` lines, as a renderer reads them anyway. */
    fun unindent(doc: String): String = doc.lineSequence()
        .map { line -> line.trimStart().let { if (it.startsWith("*")) " $it" else it } }
        .joinToString("\n")

    fun extract(sources: Path, jar: Path): List<ClassDocs> {
        val surface = publicSurface(jar)
        val out = ArrayList<ClassDocs>()
        Files.walk(sources).use { stream ->
            stream.filter { it.toString().endsWith(".java") }.sorted().forEach { file ->
                val rel = sources.relativize(file).toString().replace(java.io.File.separatorChar, '/')
                val pkg = rel.substringBeforeLast('/', "").replace('/', '.')
                val top = (if (pkg.isEmpty()) "" else "$pkg.") + rel.substringAfterLast('/').removeSuffix(".java")
                if (top !in surface) return@forEach
                for ((owner, values) in JavaSourceDocs.scan(Files.readString(file), rawDocs = true)) {
                    val members = surface[owner] ?: continue
                    if (!owner.startsWith("$pkg.") && pkg.isNotEmpty()) continue
                    val seen = HashSet<String>()
                    val methods = values.filter { it.name.isNotEmpty() }
                        .filter { "${it.name}/${it.names.size}" in members && seen.add("${it.name}/${it.names.size}") }
                        .map { MethodDocs(it.name, it.names, it.doc?.let(::unindent)) }
                    val classDoc = values.firstOrNull { it.name.isEmpty() }?.doc?.let(::unindent)
                    if (methods.isEmpty() && classDoc == null) continue
                    out += ClassDocs(pkg, owner.removePrefix("$pkg."), classDoc, methods)
                }
            }
        }
        return out
    }
}
