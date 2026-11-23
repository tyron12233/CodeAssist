package dev.ide.build.split

import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Splits the classes headed for R8 in two: the toolchain (by package) and everything else.
 *
 * Registered as the transform of AGP's `ScopedArtifact.CLASSES` for the whole app, so [programJar] is what R8
 * shrinks. The toolchain classes go to [toolchainJar], which R8 only sees on its classpath and which is dexed
 * on its own; [keepRules] keeps whatever the toolchain uses in the program (see [ToolchainKeepRules]).
 *
 * Non-class entries stay with the program, as before. A class name seen twice keeps its first copy, the
 * order AGP lists the inputs in.
 */
@CacheableTask
abstract class SplitToolchainClassesTask : DefaultTask() {
    @get:Classpath abstract val allJars: ListProperty<RegularFile>
    @get:Classpath abstract val allDirectories: ListProperty<Directory>

    /** Internal-name prefixes (`org/jetbrains/kotlin/`) of the classes that leave R8's input. */
    @get:Input abstract val toolchainPackages: ListProperty<String>

    @get:OutputFile abstract val programJar: RegularFileProperty
    @get:OutputFile abstract val toolchainJar: RegularFileProperty
    @get:OutputFile abstract val keepRules: RegularFileProperty

    @TaskAction
    fun split() {
        val prefixes = toolchainPackages.get()
        fun isToolchain(internalName: String) = prefixes.any { internalName.startsWith(it) }

        val written = HashSet<String>()
        var toolchainCount = 0
        val program = HashMap<String, ToolchainKeepRules.ProgramClass>()
        var programCount = 0

        zipOut(programJar.get().asFile).use { prog ->
            zipOut(toolchainJar.get().asFile).use { tool ->
                forEachEntry { path, bytes ->
                    if (!written.add(path)) return@forEachEntry
                    val className = classNameOf(path)
                    when {
                        className != null && isToolchain(className) -> {
                            tool.put(path, bytes)
                            toolchainCount++
                        }
                        else -> {
                            prog.put(path, bytes)
                            if (className != null) {
                                program[className] = describe(bytes)
                                programCount++
                            }
                        }
                    }
                }
            }
        }

        // A second pass over the jar just written, so the toolchain's ~270 MB of classes is never held at once.
        val rules = ToolchainKeepRules(program)
        ZipFile(toolchainJar.get().asFile).use { zip ->
            for (e in zip.entries()) rules.scan(zip.getInputStream(e).use { it.readBytes() })
        }
        keepRules.get().asFile.writeText(rules.render())
        logger.lifecycle(
            "toolchain split: $toolchainCount toolchain classes out of R8, $programCount to R8; " +
                "kept for the toolchain: ${rules.classCount} classes, ${rules.memberCount} members",
        )
    }

    /** Every entry of every input, directories first (the module's own classes), then jars, in AGP's order. */
    private fun forEachEntry(action: (String, ByteArray) -> Unit) {
        for (dir in allDirectories.get()) {
            val root = dir.asFile
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                action(f.relativeTo(root).invariantSeparatorsPath, f.readBytes())
            }
        }
        for (jar in allJars.get()) {
            ZipFile(jar.asFile).use { zip ->
                for (e in zip.entries()) {
                    if (e.isDirectory) continue
                    action(e.name, zip.getInputStream(e).use { it.readBytes() })
                }
            }
        }
    }

    private fun classNameOf(path: String): String? {
        if (!path.endsWith(".class") || path.startsWith("META-INF/")) return null
        if (path == "module-info.class" || path.endsWith("/module-info.class")) return null
        return path.removeSuffix(".class")
    }

    private fun describe(bytes: ByteArray): ToolchainKeepRules.ProgramClass {
        var superName: String? = null
        var interfaces = emptyList<String>()
        val fields = HashSet<String>()
        val methods = HashSet<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, n: String, s: String?, sup: String?, itf: Array<out String>?) {
                superName = sup
                interfaces = itf?.toList().orEmpty()
            }
            override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? {
                fields += "$n:$d"; return null
            }
            override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): MethodVisitor? {
                methods += "$n$d"; return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ToolchainKeepRules.ProgramClass(superName, interfaces, fields, methods)
    }

    private fun zipOut(file: File): ZipOutputStream {
        file.parentFile.mkdirs()
        return ZipOutputStream(file.outputStream().buffered())
    }

    /** Fixed timestamps, so the jars (and everything keyed on them) are reproducible. */
    private fun ZipOutputStream.put(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path).apply { time = 0L })
        write(bytes)
        closeEntry()
    }
}
