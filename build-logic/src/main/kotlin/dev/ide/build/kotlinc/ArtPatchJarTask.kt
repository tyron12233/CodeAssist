package dev.ide.build.kotlinc

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Applies the [ArtPatchPasses] to a jar that reaches a device WITHOUT going through the app's dex, and so
 * without AGP's instrumentation: the R8 jar, which is dexed on its own into the asset a forked VM runs.
 *
 * Every class a pass [handles][ArtPatchPasses.handles] is rewritten; everything else is copied as it is. The
 * passes that reroute calls send them to a shim class the app carries, which a forked VM's classpath does not
 * have, so the shim's class files ([shims]) are written into the jar beside the code that now calls them.
 *
 * No frames are recomputed: the passes used on such a jar swap one call for another with the same stack
 * effect. A pass that changes a method's shape would need the jar's classpath to compute frames, which this
 * task does not have.
 */
@CacheableTask
abstract class ArtPatchJarTask : DefaultTask() {

    @get:Classpath
    abstract val inputJar: ConfigurableFileCollection

    /** Class files to add (as a file tree rooted at the package root), typically the shims the passes call. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shims: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun patch() {
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        var rewritten = 0
        val written = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                if (!written.add(name)) return
                zos.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                zos.write(bytes)
                zos.closeEntry()
            }
            ZipFile(inputJar.singleFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val fqn = entry.name.removeSuffix(".class").replace('/', '.')
                    if (!entry.name.endsWith(".class") || !ArtPatchPasses.handles(fqn)) {
                        put(entry.name, bytes)
                        continue
                    }
                    val reader = ClassReader(bytes)
                    val writer = ClassWriter(reader, 0)
                    reader.accept(ArtPatchPasses.visitorFor(fqn, writer) ?: writer, 0)
                    val patched = writer.toByteArray()
                    if (!patched.contentEquals(bytes)) rewritten++
                    put(entry.name, patched)
                }
            }
            shims.asFileTree.visit {
                if (!isDirectory) put(relativePath.pathString, file.readBytes())
            }
        }
        logger.lifecycle("${name}: $rewritten class(es) rewritten, ${shims.asFileTree.files.size} shim class file(s) added")
    }

    private companion object {
        /** Fixed zip-entry time (1980-01-01), so the same inputs give the same jar. */
        const val FIXED_TIME = 315_532_800_000L
    }
}
