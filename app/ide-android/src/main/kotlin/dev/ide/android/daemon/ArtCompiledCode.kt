package dev.ide.android.daemon

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Whether ART has compiled output (`oat/<isa>/base.odex` or `.vdex`) for this app's APK.
 *
 * Without it, every process start opens the APK's dex straight from the zip and verifies all of it on the main
 * thread before any of our code runs. The APK carries close to 200 MB of dex, so on a slow phone that takes
 * long enough for the system to call the start an ANR (Play: "Executing service ... BuildDaemonService", main
 * thread in `DexFileVerifier` under `ApplicationLoaders.getClassLoader`). It happens right after an update,
 * before background dexopt has run, or when the phone was too short of storage to compile.
 *
 * Only answers "the files are there", not "ART will accept them". A false answer costs a warm first build, not
 * correctness, so anything unexpected counts as not compiled.
 */
object ArtCompiledCode {

    fun present(context: Context): Boolean = runCatching {
        val apk = File(context.applicationInfo.sourceDir)
        val isa = instructionSet() ?: return false
        val dir = File(apk.parentFile, "oat/$isa")
        val base = apk.name.substringBeforeLast('.')
        File(dir, "$base.odex").exists() || File(dir, "$base.vdex").exists()
    }.getOrDefault(false)

    /** ART's name for this process's instruction set: the oat subdirectory it writes. */
    private fun instructionSet(): String? {
        val abis = if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return when (abis.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            "riscv64" -> "riscv64"
            else -> null
        }
    }
}
