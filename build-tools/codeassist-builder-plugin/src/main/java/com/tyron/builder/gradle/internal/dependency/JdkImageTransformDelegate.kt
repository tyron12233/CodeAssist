package com.tyron.builder.gradle.internal.dependency

import com.android.SdkConstants
import com.tyron.builder.gradle.internal.packaging.JarCreatorFactory
import com.tyron.builder.packaging.JarFlinger
import com.android.ide.common.process.CachedProcessOutputHandler
import com.android.ide.common.process.LoggedProcessOutputHandler
import com.android.ide.common.process.ProcessExecutor
import com.android.ide.common.process.ProcessInfoBuilder
import com.android.utils.FileUtils
import com.android.utils.ILogger
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import org.gradle.api.JavaVersion
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipFile
import kotlin.streams.toList

/**
 * Processes a Jar containing the system module classes into a JDK Image,
 * for use via the --system option of javac and other Java-related tools.
 *
 * As per the discussion in b/154357088, the input for this process is, properly, the
 * core-for-system-modules.jar included starting in SDK 30. The steps taken to transform
 * the Jar are discussed in b/63986449.
 */
class JdkImageTransformDelegate(
    val systemModulesJar: File,
    val workDir: File,
    val outDir: File,
    val jdkTools: JdkTools
) {
    /**
     * Links a JDK Image from [systemModulesJar] in [outDir], using [workDir]
     * for the output of any intermediate operations. This is done via the "jlink" tool
     * as exposed by [jdkTools].
     */
    fun run() {
        Preconditions.checkArgument(!outDir.exists() && outDir.parentFile.isDirectory)

        // jlink takes a directory of JMOD files as inputs
        val jmodFile = makeJmodFile()
        val jmodDir = jmodFile.parentFile

        jdkTools.linkJmodsIntoJdkImage(
            jmodDir,
            "java.base",
            outDir
        )

        copyJrtFsJar(outDir, jdkTools)
    }

    /**
     * Creates a "module-info.java" file describing the contents of [systemModulesJar]
     */
    fun makeModuleDescriptorJava(): File {
        val moduleInfoJavaContent = generateModuleDescriptor(
            "java.base",
            listOf(systemModulesJar)
        )
        val moduleInfoJava = workDir.resolve("module-info.java")
        FileUtils.writeToFile(moduleInfoJava, moduleInfoJavaContent)

        return moduleInfoJava
    }

    /**
     * Creates and compiles a "module-info.class" file describing the contents of [systemModulesJar]
     */
    @VisibleForTesting
    internal fun makeModuleInfoClass(): File {
        val moduleInfoJava = makeModuleDescriptorJava()
        jdkTools.compileModuleDescriptor(
            moduleInfoJava,
            systemModulesJar,
            workDir
        )

        val moduleInfoClass = workDir.resolve("module-info.class")
        Preconditions.checkState(
            moduleInfoClass.exists(),
            "Expected compiled module descriptor file to be created at %s",
            moduleInfoClass
        )

        return moduleInfoClass
    }

    /**
     * Creates a Modular Jar from [systemModulesJar] by compiling and adding a module descriptor
     */
    @VisibleForTesting
    internal fun makeModuleJar(): File {
        val moduleInfoClass = makeModuleInfoClass()

        val moduleJar = workDir.resolve("module.jar")
        createJar(moduleInfoClass, listOf(systemModulesJar), moduleJar)

        return moduleJar
    }

    /**
     * Transforms [systemModulesJar] into a JMOD file
     */
    @VisibleForTesting
    internal fun makeJmodFile(): File {
        val moduleName = "java.base"
        val jlinkVersion = jdkTools.jlinkVersion
        val jmodDir = workDir.resolve("jmod")
        FileUtils.mkdirs(jmodDir)
        val jmodFile = FileUtils.join(jmodDir, "$moduleName.jmod")

        val moduleJar = makeModuleJar()

        jdkTools.createJmodFromModularJar(
            jmodFile,
            jlinkVersion,
            moduleJar
        )

        return jmodFile
    }

}

class JdkTools(
    val javaHome: File,
    val processExecutor: ProcessExecutor,
    val logger: ILogger
) {
    val jrtFsLocation: File = javaHome.resolve("lib").resolve("jrt-fs.jar")

    val jlinkVersion: String by lazy {
        val jlinkExecutable = javaHome.resolve("bin").resolve("jlink".optionalExe())
        Preconditions.checkArgument(
            jlinkExecutable.exists(),
            "jlink executable %s does not exist.",
            jlinkExecutable
        )

        val pib = ProcessInfoBuilder().apply {
            setExecutable(jlinkExecutable)
            addArgs("--version")
        }

        val processOutputHandler = CachedProcessOutputHandler()
        processExecutor.execute(
            pib.createProcess(),
            processOutputHandler
        ).rethrowFailure().assertNormalExitValue()

        val processOutput = processOutputHandler.processOutput.standardOutputAsString.trim()

        // Try to extract only major version in order to reduce build cache misses - b/234820480.
        // If we fail, get the whole version.
        return@lazy  try {
            JavaVersion.toVersion(processOutput).majorVersion
        } catch (t: Throwable) {
            processOutput
        }
    }

    fun compileModuleDescriptor(
        moduleInfoJava: File,
        systemModulesJar: File,
        outDir: File
    ) {
        val classpathArgValue = systemModulesJar.absolutePath
        val javacExecutable = javaHome.resolve("bin").resolve("javac".optionalExe())

        Preconditions.checkArgument(
            javacExecutable.exists(),
            "javac executable %s does not exist.",
            javacExecutable
        )

        val pib = ProcessInfoBuilder().apply {
            setExecutable(javacExecutable)
            addArgs("--system=none")
            addArgs("--patch-module=java.base=$classpathArgValue")
            addArgs("-d", outDir.absolutePath)
            addArgs(moduleInfoJava.absolutePath)
        }

        processExecutor.execute(
            pib.createProcess(),
            LoggedProcessOutputHandler(logger)
        ).rethrowFailure().assertNormalExitValue()
    }

    fun createJmodFromModularJar(
        jmodFile: File,
        jlinkVersion: String,
        moduleJar: File
    ) {
        val jmodExecutable = javaHome.resolve("bin").resolve("jmod".optionalExe())
        Preconditions.checkArgument(
            jmodExecutable.exists(),
            "jmod executable %s does not exist.",
            jmodExecutable
        )
        val pib = ProcessInfoBuilder().apply {
            setExecutable(jmodExecutable)
            addArgs("create")
            addArgs("--module-version", jlinkVersion)
            addArgs("--target-platform", "android")
            addArgs("--class-path", moduleJar.absolutePath)
            addArgs(jmodFile.absolutePath)
        }

        processExecutor.execute(
            pib.createProcess(),
            LoggedProcessOutputHandler(logger)
        ).rethrowFailure().assertNormalExitValue()
    }

    fun linkJmodsIntoJdkImage(jmodDir: File, moduleName: String, outDir: File) {
        val jlinkExecutable = javaHome.resolve("bin").resolve("jlink".optionalExe())
        Preconditions.checkArgument(
            jlinkExecutable.exists(),
            "jlink executable %s does not exist.",
            jlinkExecutable
        )

        val pib = ProcessInfoBuilder().apply {
            setExecutable(jlinkExecutable)
            addArgs("--module-path", jmodDir.absolutePath)
            addArgs("--add-modules", moduleName)
            addArgs("--output", outDir.absolutePath)
            addArgs("--disable-plugin", "system-modules")
        }

        processExecutor.execute(
            pib.createProcess(),
            LoggedProcessOutputHandler(logger)
        ).rethrowFailure().assertNormalExitValue()
    }

}

/**
 * Modeled after the Android platform build method for parsing module-info.java out of a list of Jars
 * See b/63986449 for more info
 */
@VisibleForTesting
internal fun generateModuleDescriptor(
    moduleName: String,
    jars: List<File>
): String {
    val stringBuilder = StringBuilder()
    stringBuilder.appendln("module $moduleName {")
    val packageNameRegex = Regex("(.*)/[^/]*.class")
    jars.asSequence().flatMap { jar ->
        ZipFile(jar).use { it.stream().map { zipEntry -> zipEntry.name }.toList() }.asSequence()
    }
        .mapNotNull { packageNameRegex.find(it)?.groupValues?.get(1)?.replace("/", ".") }
        .toSortedSet()
        .forEach { stringBuilder.appendln("    exports $it;") }
    stringBuilder.appendln("}")
    return stringBuilder.toString()
}

@VisibleForTesting
internal fun createJar(moduleInfoClass: File, inJars: List<File>, outputJar: File) {
    JarCreatorFactory.make(outputJar.toPath()).use {
        it.setCompressionLevel(Deflater.NO_COMPRESSION)
        it.addFile(moduleInfoClass.name, moduleInfoClass.toPath())
        inJars.forEach { inJar ->
            it.addJar(inJar.toPath())
        }
    }
}

private fun copyJrtFsJar(outDir: File, jdkTools: JdkTools) {
    val source = jdkTools.jrtFsLocation

    val copiedLibsDir = FileUtils.mkdirs(outDir.resolve("lib"))
    val destination = copiedLibsDir.resolve(source.name)

    // Ignore manifest.mf as it contains unnecessary data causing build cache misses, b/234820480.
    JarFlinger(destination.toPath()) { !it.equals("meta-inf/manifest.mf", ignoreCase = true) }.use {
        it.addJar(source.toPath())
    }
}

internal fun String.optionalExe() =
    if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_WINDOWS) this + ".exe" else this


internal const val JRT_FS_JAR = "jrt-fs.jar"