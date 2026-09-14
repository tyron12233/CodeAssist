package dev.ide.android.support.tasks

import dev.ide.build.ClassTransform
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `instrumentClasses<Variant>`: runs the contributed [ClassTransform]s over everything about to be dexed,
 * writing rewritten copies that the dex tasks consume instead of the originals (AGP's
 * `transformClassesWithAsm`). The originals are never modified: a compile output is its compiler's, and a
 * dependency jar is shared with every other project on the machine.
 *
 * Library jars are cached by content: a jar's rewritten copy is keyed by the hash of its bytes plus the
 * transforms' ids and versions, so the hundreds of megabytes of unchanged dependencies in a large app are
 * rewritten once and reused on every later build. Project classes are rewritten each run, which is right:
 * they are what changed.
 *
 * A transform that throws on one class fails the build rather than silently shipping the original: a
 * rewrite that was supposed to make a class runnable and did not is a crash at runtime, and one that is
 * reported at build time instead is the whole point of doing it here.
 */
internal class InstrumentClassesTask(
    override val name: TaskName,
    private val moduleName: String,
    /** Compiled class directories (the app's own output, and its modules'). */
    private val classDirs: List<Path>,
    /** Jars: sub-module artifacts and external libraries. */
    private val jars: List<Path>,
    private val transforms: List<ClassTransform>,
    /** Staging root; [instrumentedClassDir] and [instrumentedJar] name what lands in it. */
    private val outDir: Path,
) : Task {

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            if (classDirs.isNotEmpty()) dirPaths("classes", classDirs)
            if (jars.isNotEmpty()) filePaths("jars", jars)
            property("transforms", transformKey(transforms))
        }

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("instrumented", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (transforms.isEmpty()) return TaskResult.Success
        val projectTransforms = transforms.filter { it.scope.includesProject() }
        val dependencyTransforms = transforms.filter { it.scope.includesDependencies() }
        var rewritten = 0
        var reused = 0
        return runCatching {
            for (dir in classDirs.filter { Files.isDirectory(it) }) {
                ctx.checkCanceled()
                rewritten += instrumentDir(dir, instrumentedClassDir(outDir, dir), projectTransforms)
            }
            for (jar in jars.filter { Files.isRegularFile(it) }) {
                ctx.checkCanceled()
                val out = instrumentedJar(outDir, jar, dependencyTransforms)
                if (Files.exists(out)) {
                    reused++
                } else {
                    instrumentJar(jar, out, dependencyTransforms)
                    rewritten++
                }
            }
            ctx.debug("${name.value}: $rewritten instrumented, $reused reused from cache ($moduleName)")
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("instrumentClasses failed: ${it.message}", it) }
    }

    /** Copy [dir] to [out], passing `.class` files through [transforms]. Returns the number of files written. */
    private fun instrumentDir(dir: Path, out: Path, transforms: List<ClassTransform>): Int {
        var count = 0
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { src ->
                val dest = out.resolve(dir.relativize(src).toString())
                Files.createDirectories(dest.parent)
                if (src.toString().endsWith(".class")) {
                    Files.write(dest, apply(transforms, classNameOf(dir.relativize(src).toString()), Files.readAllBytes(src)))
                } else {
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
                count++
            }
        }
        return count
    }

    private fun instrumentJar(jar: Path, out: Path, transforms: List<ClassTransform>) {
        Files.createDirectories(out.parent)
        val tmp = out.resolveSibling("${out.fileName}.tmp")
        ZipFile(jar.toFile()).use { zip ->
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(tmp))).use { zos ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val bytes = BufferedInputStream(zip.getInputStream(entry)).use { it.readBytes() }
                    val out1 = if (entry.name.endsWith(".class")) {
                        apply(transforms, classNameOf(entry.name), bytes)
                    } else {
                        bytes
                    }
                    // A fresh entry rather than the original: a STORED entry carries the source's CRC and
                    // size, which no longer describe rewritten bytes.
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(out1)
                    zos.closeEntry()
                }
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun apply(transforms: List<ClassTransform>, className: String, bytes: ByteArray): ByteArray {
        var current = bytes
        for (transform in transforms) {
            val result = try {
                transform.transform(className, current)
            } catch (e: Throwable) {
                throw IllegalStateException("transform '${transform.id}' failed on $className: ${e.message}", e)
            }
            if (result != null) current = result
        }
        return current
    }

    /** `a/b/C.class` (an archive path, always `/`-separated once relativized) → `a.b.C`. */
    private fun classNameOf(path: String): String =
        path.replace('\\', '/').removeSuffix(".class").replace('/', '.')

    companion object {
        /** Where [dir]'s instrumented copy lands. A pure function of the inputs, so the task registration
         *  can name the dex inputs without having run the task. */
        fun instrumentedClassDir(outDir: Path, dir: Path): Path =
            outDir.resolve("classes").resolve(shortHash(dir.toAbsolutePath().toString()))

        /** Where [jar]'s instrumented copy lands: keyed by content + transform set, so it is reusable
         *  across builds and across projects sharing the staging root. */
        fun instrumentedJar(outDir: Path, jar: Path, transforms: List<ClassTransform>): Path =
            outDir.resolve("jars")
                .resolve("${contentHash(jar)}-${transformKey(transforms).let { shortHash(it) }}")
                .resolve(jar.fileName.toString())

        fun transformKey(transforms: List<ClassTransform>): String =
            transforms.joinToString(",") { "${it.id}@${it.version}:${it.scope}" }

        private fun contentHash(file: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }

        private fun shortHash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)
    }
}
