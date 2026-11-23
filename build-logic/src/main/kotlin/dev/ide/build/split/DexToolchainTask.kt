package dev.ide.build.split

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * D8-dexes the toolchain that [SplitToolchainClassesTask] took out of R8's input.
 *
 * Same settings R8 gives the rest of the app: release mode, the variant's min-api, and the core-library
 * desugaring configuration (calls to java.* APIs newer than min-api are rewritten to the `j$` library the
 * app ships). The toolchain only changes when a dependency does, so this is cacheable and normally skipped.
 */
@CacheableTask
abstract class DexToolchainTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:Classpath abstract val toolchainJar: RegularFileProperty

    /** The D8/R8 jar: the same version AGP shrinks the rest of the app with. */
    @get:Classpath abstract val d8Classpath: ConfigurableFileCollection

    /** The desugar_jdk_libs_configuration jar; its `META-INF/desugar/d8/desugar.json` is the config. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val desugarConfigJar: ConfigurableFileCollection

    @get:CompileClasspath abstract val bootClasspath: ConfigurableFileCollection
    @get:Input abstract val minApi: Property<Int>
    @get:Input abstract val maxHeap: Property<String>

    @get:OutputDirectory abstract val dexDir: DirectoryProperty

    @TaskAction
    fun dex() {
        val out = dexDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val config = File(temporaryDir, "desugar.json")
        ZipFile(desugarConfigJar.singleFile).use { zip ->
            val entry = zip.getEntry("META-INF/desugar/d8/desugar.json")
                ?: error("no META-INF/desugar/d8/desugar.json in ${desugarConfigJar.singleFile}")
            config.writeBytes(zip.getInputStream(entry).use { it.readBytes() })
        }
        exec.javaexec {
            classpath = d8Classpath
            mainClass.set("com.android.tools.r8.D8")
            maxHeapSize = maxHeap.get()
            args = buildList {
                addAll(listOf("--release", "--min-api", minApi.get().toString(), "--desugared-lib", config.absolutePath))
                bootClasspath.files.forEach { addAll(listOf("--lib", it.absolutePath)) }
                addAll(listOf("--output", out.absolutePath, toolchainJar.get().asFile.absolutePath))
            }
        }
        val dex = out.listFiles { f -> f.name.endsWith(".dex") }.orEmpty()
        logger.lifecycle("toolchain dex: ${dex.size} files, ${dex.sumOf { it.length() } / (1024 * 1024)} MB")
    }
}
