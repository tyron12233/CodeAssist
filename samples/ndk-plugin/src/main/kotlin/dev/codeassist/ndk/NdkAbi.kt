package dev.codeassist.ndk

/**
 * The Android ABIs the bundled toolchain can emit code for.
 *
 * Two different questions hide behind "which ABI":
 *
 *  - the TARGET: what a module's `abis` asks to be built. The bundled LLVM carries the AArch64, ARM and X86
 *    backends and the sysroot holds the link libraries of all four, so any of these can be built from any
 *    device the plugin runs on;
 *  - the HOST: what the compiler itself runs on. That is the device's own ABI, and it is only a program
 *    this device can execute, so it is the one Run launches (see [NdkToolchain.hostAbi]).
 *
 * [libTriple] is not always [clangArch]: clang is told `armv7a-linux-androideabi`, but the NDK's sysroot names
 * its directory `arm-linux-androideabi`, and clang derives that name itself.
 */
enum class NdkAbi(
    val abiName: String,
    private val clangArch: String,
    val libTriple: String,
    /** Flags the NDK's own toolchain adds for this ABI and the clang driver does not default. */
    val extraFlags: List<String> = emptyList(),
) {
    ARM64("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"),
    ARM32("armeabi-v7a", "armv7a-linux-androideabi", "arm-linux-androideabi"),
    // 32-bit x86 Android does not guarantee a 16-byte aligned stack at a call from the framework, so the NDK
    // realigns it in every function that needs it. Without this, SSE code can fault on a misaligned spill.
    X86("x86", "i686-linux-android", "i686-linux-android", listOf("-mstackrealign")),
    X86_64("x86_64", "x86_64-linux-android", "x86_64-linux-android");

    /** Whether its programs are 64-bit, which picks the system linker that loads them (`linker64` or `linker`). */
    val is64Bit: Boolean get() = this == ARM64 || this == X86_64

    /** What goes after `--target=`: the architecture with the API level the sysroot's stubs were taken at. */
    val target: String get() = "$clangArch${NdkToolchain.API_LEVEL}"

    companion object {
        /** The ABI a device's primary [android.os.Build.SUPPORTED_ABIS] entry names, or null when unknown. */
        fun byName(name: String): NdkAbi? = values().firstOrNull { it.abiName == name }

        /** Every name this plugin accepts in a module's `abis`, for a message that has to list them. */
        val names: List<String> get() = values().map { it.abiName }

        /**
         * The ABI this device runs, from its supported list in preference order.
         *
         * The first entry we know is the one the installer unpacked the plugin's executables for, so it is
         * also the one whose programs can be started here. arm64 when the list says nothing (a JVM test).
         */
        fun forDevice(supported: List<String>): NdkAbi =
            supported.firstNotNullOfOrNull { byName(it) } ?: ARM64
    }
}
