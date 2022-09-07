package com.tyron.builder.internal.compiler

import com.android.SdkConstants
import com.android.SdkConstants.*
import com.android.ide.common.internal.WaitableExecutor
import com.android.ide.common.process.ProcessExecutor
import com.android.ide.common.process.ProcessInfoBuilder
import com.android.ide.common.process.ProcessOutputHandler
import com.android.io.CancellableFileIo
import com.android.sdklib.BuildToolInfo
import com.android.sdklib.BuildToolInfo.PathId
import com.android.utils.FileUtils
import com.android.utils.ILogger
import com.google.common.annotations.VisibleForTesting
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

private const val LIBCLCORE_BC = "libclcore.bc"

/**
 * Compiles Renderscript files.
 */
class RenderScriptProcessor(
    private val sourceFolders: Collection<File>,
    private val importFolders: Collection<File>,
    private val sourceOutputDir: File,
    private val resOutputDir: File,
    private val objOutputDir: File,
    private val libOutputDir: File,
    private val buildToolInfo: BuildToolInfo,
    targetApi: Int,
    private val optimizationLevel: Int,
    private val ndkMode: Boolean,
    private val supportMode: Boolean,
    private val useAndroidX: Boolean,
    private val abiFilters: Set<String>,
    private val logger: ILogger
) {
    // These indicate whether to compile with ndk for 32 or 64 bits
    private val is32Bit: Boolean
    private val is64Bit: Boolean

    private val rsLib: Path?
    private val libClCore = mutableMapOf<String, Path>()

    private val abis32: Array<Abi>
    private val abis64: Array<Abi>

    private val actualTargetApi = if (supportMode) maxOf(18, targetApi) else maxOf(11, targetApi)

    private val genericRawFolder: File
        get() = File(resOutputDir, SdkConstants.FD_RES_RAW)

    @VisibleForTesting
    // ABI representation with device name, toolchain triple and the linker arguments
    class Abi constructor(
        val device: String,
        val toolchain: String,
        private val linkerArgs: Array<String>
    ) {
        val linker: PathId = PathId.LD_ARM

        // Add the required linker flavor to arguments
        fun getLinkerArgs(): Array<String> = arrayOf("-flavor", "ld") + linkerArgs
    }

    private enum class AbiType {
        BIT_32,
        BIT_64
    }

    init {
        // Set which linker to use for abis based on target build tools revision
        abis32 = getAbis(AbiType.BIT_32)
        abis64 = getAbis(AbiType.BIT_64)

        if (supportMode) {
            val rs = buildToolInfo.location.resolve("renderscript")
            rsLib = rs.resolve("lib").toPath()
            val bcFolder = rsLib.resolve("bc")
            for (abi in abis32) {
                val rsClCoreFile = bcFolder.resolve(abi.device + '/' + LIBCLCORE_BC)
                if (CancellableFileIo.exists(rsClCoreFile)) {
                    libClCore[abi.device] = rsClCoreFile
                }
            }
            for (abi in abis64) {
                val rsClCoreFile = bcFolder.resolve(abi.device + '/' + LIBCLCORE_BC)
                if (CancellableFileIo.exists(rsClCoreFile)) {
                    libClCore[abi.device] = rsClCoreFile
                }
            }
        } else {
            rsLib = null
        }

        // If no abi filters were set, assume compilation for both 32 bit and 64 bit
        if (abiFilters.isEmpty()) {
            is32Bit = true
            is64Bit = true
        } else {
            // Check if abi filters contains an abi that is 32 bit
            is32Bit = abis32.any { abi -> abiFilters.contains(abi.device) }

            // Check if abi filters contains an abi that is 64 bit
            is64Bit = abis64.any { abi -> abiFilters.contains(abi.device) }
        }

        // Api < 21 does not support 64 bit ndk compilation
        if (targetApi < 21 && is64Bit && ndkMode) {
            throw RuntimeException(
                "Api version $targetApi does not support 64 bit ndk compilation"
            )
        }
    }

    fun build(
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler
    ) {
        val renderscriptFiles = mutableListOf<File>()
        for (dir in sourceFolders) {
            DirectoryWalker.builder()
                .root(dir.toPath())
                .extensions("rs", "fs")
                .action { _, path -> renderscriptFiles.add(path.toFile()) }
                .build()
                .walk()
        }

        if (renderscriptFiles.isEmpty()) {
            return
        }

        // get the env var
        val env = mutableMapOf<String, String>()
        if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_DARWIN) {
            env["DYLD_LIBRARY_PATH"] = buildToolInfo.location.toPath().toAbsolutePath().toString()
        } else if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_LINUX) {
            env["LD_LIBRARY_PATH"] = buildToolInfo.location.toPath().toAbsolutePath().toString()
        }

        doMainCompilation(renderscriptFiles, processExecutor, processOutputHandler, env)

        if (supportMode) {
            createSupportFiles(processExecutor, processOutputHandler, env)
        }
    }

    private fun getArchSpecificRawFolder(architecture: String): File {
        return FileUtils.join(resOutputDir, SdkConstants.FD_RES_RAW, "bc$architecture")
    }

    private fun doMainCompilation(
        inputFiles: List<File>,
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler,
        env: Map<String, String?>
    ) {
        val architectures: MutableList<String> = mutableListOf()
        if (is32Bit) {
            architectures.add("32")
        }
        if (is64Bit) {
            architectures.add("64")
        }

        if (actualTargetApi >= 21 && ndkMode) {
            // Add the arguments that are specific to each run.
            // Then, for each arch specific folder, run the compiler once.
            for (arch in architectures) {
                compileBCFiles(
                    inputFiles,
                    processExecutor,
                    processOutputHandler,
                    env,
                    getArchSpecificRawFolder(arch),
                    arch)
            }
        } else {
            compileBCFiles(
                inputFiles,
                processExecutor,
                processOutputHandler,
                env,
                genericRawFolder)
        }
    }

    private fun compileBCFiles(
        inputFiles: List<File>,
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler,
        env: Map<String, String?>,
        outputFile: File,
        arch: String? = null
    ) {
        val builder = ProcessInfoBuilder()
        val renderscript = buildToolInfo.getPath(PathId.LLVM_RS_CC)
        check(renderscript?.let { File(it).isFile } == true) { "${PathId.LLVM_RS_CC} is missing" }
        // compile all the files in a single pass
        builder.setExecutable(renderscript)
        builder.addEnvironments(env)

        val rsPath = buildToolInfo.getPath(PathId.ANDROID_RS)
        val rsClangPath =
            buildToolInfo.getPath(PathId.ANDROID_RS_CLANG)
        // First add all the arguments that are common between the runs.
        // add all import paths
        builder.addArgs("-I")
        builder.addArgs(rsPath)
        builder.addArgs("-I")
        builder.addArgs(rsClangPath)
        for (importPath in importFolders) {
            if (importPath.isDirectory) {
                builder.addArgs("-I")
                builder.addArgs(importPath.absolutePath)
            }
        }
        if (supportMode) {
            if (useAndroidX) {
                builder.addArgs("-rs-package-name=$FN_ANDROIDX_RENDERSCRIPT_PACKAGE")
            } else {
                builder.addArgs("-rs-package-name=$FN_RENDERSCRIPT_V8_PACKAGE")
            }
        }
        // source output
        builder.addArgs("-p")
        builder.addArgs(sourceOutputDir.absolutePath)
        builder.addArgs("-target-api")

        builder.addArgs(actualTargetApi.toString())
        // input files
        for (sourceFile in inputFiles) {
            builder.addArgs(sourceFile.absolutePath)
        }
        if (ndkMode) {
            builder.addArgs("-reflect-c++")
        }
        builder.addArgs("-O")
        builder.addArgs(optimizationLevel.toString())
        // TODO(146349244): investigate this
        // Due to a device side bug, let's not enable this at this time.
        //        if (mDebugBuild) {
        //            command.add("-g");
        //        }

        // Add the rest of the arguments and run the compiler once
        // res output
        builder.addArgs("-o")
        // the renderscript compiler doesn't expect the top res folder,
        // but the raw folder directly.
        builder.addArgs(outputFile.absolutePath)

        if (arch != null) {
            builder.addArgs("-m$arch")
        }

        val result = processExecutor.execute(builder.createProcess(), processOutputHandler)
        result.rethrowFailure().assertNormalExitValue()
    }

    private fun createSupportFiles(
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler,
        env: Map<String, String>
    ) {
        // get the generated BC files.
        if (actualTargetApi < 21) {
            val rawFolder = genericRawFolder
            createSupportFilesHelper(rawFolder, abis32, processExecutor, processOutputHandler, env)
        } else {
            val rawFolder32 = getArchSpecificRawFolder("32")
            createSupportFilesHelper(
                rawFolder32,
                abis32,
                processExecutor,
                processOutputHandler,
                env
            )
            val rawFolder64 = getArchSpecificRawFolder("64")
            createSupportFilesHelper(
                rawFolder64,
                abis64,
                processExecutor,
                processOutputHandler,
                env
            )
        }
    }

    private fun createSupportFilesHelper(
        rawFolder: File,
        abis: Array<Abi>,
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler,
        env: Map<String, String>
    ) {
        val mExecutor = WaitableExecutor.useGlobalSharedThreadPool()

        val files = mutableListOf<File>()
        DirectoryWalker.builder()
            .root(rawFolder.toPath())
            .extensions(EXT_BC)
            .action { _, path -> files.add(path.toFile()) }
            .build()
            .walk()

        for (bcFile in files) {
            val name = bcFile.name
            val objName = name.replace("\\.bc".toRegex(), ".o")
            val soName = "librs." + name.replace("\\.bc".toRegex(), ".so")

            for (abi in abis) {
                if (abiFilters.isNotEmpty() && !abiFilters.contains(abi.device)) {
                    continue
                }
                // only build for the ABIs bundled in Build-Tools.
                if (libClCore[abi.device] == null) {
                    // warn the user to update Build-Tools if the desired ABI is not found.
//                    logger.warning(
//                        """|Skipped RenderScript support mode compilation for ${abi.device} : required components not found in Build-Tools ${buildToolInfo.revision}
//                           |Please check and update your BuildTools.""".trimMargin("|")
//                    )
                    continue
                }

                // make sure the dest folders exist
                val objAbiFolder = File(objOutputDir, abi.device)
                if (!objAbiFolder.isDirectory && !objAbiFolder.mkdirs()) {
                    throw IOException("Unable to create dir ${objAbiFolder.absolutePath}")
                }

                val libAbiFolder = File(libOutputDir, abi.device)
                if (!libAbiFolder.isDirectory && !libAbiFolder.mkdirs()) {
                    throw IOException("Unable to create dir ${libAbiFolder.absolutePath}")
                }

                mExecutor.execute {
                    val objFile = createSupportObjFile(
                        bcFile,
                        abi,
                        objName,
                        objAbiFolder,
                        processExecutor,
                        processOutputHandler,
                        env
                    )
                    createSupportLibFile(
                        objFile,
                        abi,
                        soName,
                        libAbiFolder,
                        processExecutor,
                        processOutputHandler,
                        env
                    )
                    null
                }
            }
        }

        mExecutor.waitForTasksWithQuickFail<Any>(true /*cancelRemaining*/)
    }

    private fun createSupportObjFile(
        bcFile: File,
        abi: Abi,
        objName: String,
        objAbiFolder: File,
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler,
        env: Map<String, String>
    ): File {

        val builder = ProcessInfoBuilder()
        builder.setExecutable(buildToolInfo.getPath(PathId.BCC_COMPAT))
        builder.addEnvironments(env)

        builder.addArgs("-O$optimizationLevel")

        val outFile = File(objAbiFolder, objName)
        builder.addArgs("-o", outFile.absolutePath)
        builder.addArgs("-fPIC")
        builder.addArgs("-shared")

        builder.addArgs("-rt-path", libClCore.getValue(abi.device).toAbsolutePath().toString())

        builder.addArgs("-mtriple", abi.toolchain)
        builder.addArgs(bcFile.absolutePath)

        processExecutor.execute(
            builder.createProcess(), processOutputHandler
        )
            .rethrowFailure().assertNormalExitValue()

        return outFile
    }

    private fun createSupportLibFile(
        objFile: File,
        abi: Abi,
        soName: String,
        libAbiFolder: File,
        processExecutor: ProcessExecutor,
        processOutputHandler: ProcessOutputHandler,
        env: Map<String, String>
    ) {
        val root = rsLib ?: Paths.get("")
        val intermediatesFolder = root.resolve("intermediates")
        val intermediatesAbiFolder = intermediatesFolder.resolve(abi.device)
        val packagedFolder = root.resolve("packaged")
        val packagedAbiFolder = packagedFolder.resolve(abi.device)
        val builder = ProcessInfoBuilder()
        builder.setExecutable(buildToolInfo.getPath(abi.linker))
        builder.addEnvironments(env)

        builder
            .addArgs(abi.getLinkerArgs())
            .addArgs("--eh-frame-hdr")
            .addArgs("-shared", "-Bsymbolic", "-z", "noexecstack", "-z", "relro", "-z", "now")

        val outFile = File(libAbiFolder, soName)
        builder.addArgs("-o", outFile.absolutePath)

        builder.addArgs(
            "-L${intermediatesAbiFolder.toAbsolutePath()}",
            "-L${packagedAbiFolder.toAbsolutePath()}",
            "-soname",
            soName,
            objFile.absolutePath,
            intermediatesAbiFolder.resolve("libcompiler_rt.a").toAbsolutePath().toString(),
            "-lRSSupport",
            "-lm",
            "-lc"
        )

        processExecutor.execute(
            builder.createProcess(), processOutputHandler
        )
            .rethrowFailure().assertNormalExitValue()
    }

    companion object {
        @JvmStatic
        fun getSupportJar(buildToolsFolder: File, useAndroidX: Boolean): File {
            return File(
                getBaseRenderscriptLibFolder(buildToolsFolder),
                if (useAndroidX) FN_ANDROIDX_RS_JAR else FN_RENDERSCRIPT_V8_JAR
            )
        }

        @JvmStatic
        fun getSupportNativeLibFolder(buildToolsFolder: File): File {
            return File(getBaseRenderscriptLibFolder(buildToolsFolder), "packaged")
        }

        @JvmStatic
        fun getSupportBlasLibFolder(buildToolsFolder: File): File {
            return File(getBaseRenderscriptLibFolder(buildToolsFolder), "blas")
        }

        @JvmStatic
        private fun getBaseRenderscriptLibFolder(buildToolsFolder: File): File {
            return File(buildToolsFolder, "renderscript/lib")
        }

        private fun getAbis(type: AbiType): Array<Abi> {

            return when (type) {
                AbiType.BIT_32 ->
                    arrayOf(
                        Abi(
                            "armeabi-v7a",
                            "armv7-none-linux-gnueabi",
                            arrayOf(
                                "-dynamic-linker",
                                "/system/bin/linker",
                                "-X",
                                "-m",
                                "armelf_linux_eabi"
                            )
                        ),
                        Abi(
                            "mips",
                            "mipsel-unknown-linux",
                            arrayOf("-EL")
                        ),
                        Abi(
                            "x86",
                            "i686-unknown-linux",
                            arrayOf("-m", "elf_i386")
                        )
                    )
                AbiType.BIT_64 ->
                    arrayOf(
                        Abi(
                            "arm64-v8a",
                            "aarch64-linux-android",
                            arrayOf("-X", "--fix-cortex-a53-843419")
                        ),
                        Abi(
                            "x86_64",
                            "x86_64-unknown-linux",
                            arrayOf("-m", "elf_x86_64")
                        )
                    )
            }
        }

        @JvmStatic
        @VisibleForTesting
        fun getAbis(abiType: String): Array<Abi>? {
            return when (abiType) {
                "32" -> getAbis(AbiType.BIT_32)
                "64" -> getAbis(AbiType.BIT_64)
                else -> null
            }
        }
    }
}
