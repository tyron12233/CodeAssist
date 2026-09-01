package dev.ide.android.support.tasks

import dev.ide.android.support.tools.HiltEntryPoints
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `transformHiltClasses`: the module's compiled classes with Hilt's `@AndroidEntryPoint` superclass rewrite
 * applied ([HiltEntryPoints]): the step the Hilt Gradle plugin contributes to an AGP build, and the one the
 * processor's `disableAndroidSuperclassValidation` option assumes has happened. Registered only for a module
 * that declares the Hilt runtime, so a project without Hilt builds exactly as before.
 *
 * The result is a **complete copy** of [classDirs] into [outDir] (rewritten entry points, everything else
 * byte-for-byte), which then REPLACES those dirs as the dex/jar input. Two alternatives were rejected:
 *  - Rewriting the compile output in place would leave the compile task's own output fingerprint changed
 *    behind its back, so `compileJava`/`compileKotlin` would be out of date on every build and never skip.
 *  - Emitting only the rewritten classes and layering that dir over the originals works for the per-class
 *    dexer (it keys by package-relative path, so the layer wins) but not for R8, which jars each program dir
 *    separately and would then see the class twice.
 *
 * The copy is incremental: a destination newer than its source is left alone, so a build that recompiled one
 * class re-emits just that one file. [HiltEntryPoints.VERSION] is stamped into [outDir] and a mismatch
 * (an IDE update that changed the rewrite) re-emits everything, which mtimes alone would not notice.
 */
internal class TransformHiltClassesTask(
    override val name: TaskName,
    /** The module's own compiled output roots (Java + Kotlin), in the order the dexer collects them: a later
     *  root wins a package-relative-path clash. */
    private val classDirs: List<Path>,
    private val outDir: Path,
) : Task {

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("classes", classDirs)
            property("transform", HiltEntryPoints.VERSION)
        }

    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("out", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        ctx.checkCanceled()
        runCatching {
            Files.createDirectories(outDir)
            invalidateOnVersionChange()

            // relpath -> source file, later root wins, the same collection order [DexArchiveBuilderTask]
            // uses for the project scope, so a Java/Kotlin output clash resolves identically either way.
            val byRel = LinkedHashMap<String, Path>()
            for (root in classDirs.filter { Files.isDirectory(it) }) {
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) }.forEach { f ->
                        byRel[root.relativize(f).toString().replace('\\', '/')] = f
                    }
                }
            }

            var copied = 0
            var rewritten = 0
            val ungenerated = ArrayList<String>()
            for ((rel, src) in byRel) {
                ctx.checkCanceled()
                val dst = outDir.resolve(rel)
                // Written after its source, so an up-to-date copy is strictly newer.
                if (Files.isRegularFile(dst) &&
                    Files.getLastModifiedTime(dst) >= Files.getLastModifiedTime(src)
                ) continue
                dst.parent?.let { Files.createDirectories(it) }
                val bytes = Files.readAllBytes(src)
                // Non-class output (kotlinc's `META-INF/*.kotlin_module`, …) is carried over untouched: this
                // dir stands in for the compile output wholesale, so anything dropped here is dropped from
                // the APK/AAR.
                val base = if (rel.endsWith(".class")) HiltEntryPoints.entryPointBase(bytes) else null
                val entryPoint = when {
                    base == null -> null
                    // Rewrite only towards a base the processor really generated and compiled. If Hilt didn't
                    // run for this module, re-pointing the class at a type that isn't there would turn "nothing
                    // is injected" into a NoClassDefFoundError at launch. Leave it alone and say so instead.
                    byRel.containsKey("$base.class") -> HiltEntryPoints.rewriteSuperclass(bytes)
                    else -> { ungenerated += base; null }
                }
                if (entryPoint != null) rewritten++
                Files.write(dst, entryPoint ?: bytes)
                copied++
            }
            prune(byRel.keys)

            ctx.logger()(
                "${name.value}: ${byRel.size} class file(s), $copied written, " +
                    "$rewritten @AndroidEntryPoint superclass rewrite(s)"
            )
            if (ungenerated.isNotEmpty()) {
                ctx.logger()(
                    "${name.value}: left ${ungenerated.size} Hilt entry point(s) unrewritten: the processor " +
                        "generated no ${ungenerated.take(3).joinToString { it.substringAfterLast('/') }}" +
                        (if (ungenerated.size > 3) ", …" else "") +
                        ". Hilt injection will not run for them."
                )
            }
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("${name.value} failed: ${it.message}", it) }
    }

    /** Drop the whole copy when it was produced by a different rewrite version, so the next pass re-emits it. */
    private fun invalidateOnVersionChange() {
        val stamp = outDir.resolve(STAMP)
        if (runCatching { Files.readString(stamp) }.getOrNull() == HiltEntryPoints.VERSION) return
        existingFiles().forEach { runCatching { Files.deleteIfExists(it) } }
        runCatching { Files.writeString(stamp, HiltEntryPoints.VERSION) }
    }

    /** Delete copies whose source class is gone (a renamed or removed type), so it can't reach the APK. */
    private fun prune(live: Set<String>) {
        for (f in existingFiles()) {
            val rel = outDir.relativize(f).toString().replace('\\', '/')
            if (rel !in live) runCatching { Files.deleteIfExists(f) }
        }
    }

    /** Every emitted file under [outDir], excluding the version stamp. */
    private fun existingFiles(): List<Path> {
        if (!Files.isDirectory(outDir)) return emptyList()
        return runCatching {
            Files.walk(outDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString() != STAMP }.collect(Collectors.toList())
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val STAMP = ".hilt-transform"
    }
}
