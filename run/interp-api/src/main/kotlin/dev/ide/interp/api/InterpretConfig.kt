package dev.ide.interp.api

import java.nio.file.Path

/**
 * How a source session behaves: where real code comes from, what it is allowed to touch, and whether an
 * unsupported statement stops the run.
 *
 * The default is the conservative one (the host's own classes for real code, the project's sandbox settings,
 * and gaps skipped), so `InterpretConfig()` is a reasonable preview configuration.
 */
class InterpretConfig(
    /**
     * The loader real (non-interpreted) classes are resolved against. This is the single most useful knob a
     * framework plugin has: **pass the plugin's own loader** (`javaClass.classLoader`) and the framework
     * bundled in the plugin's APK is bridged as real, dexed, full-speed code, leaving only the user's own
     * source interpreted. Null uses the host's loader, which sees the IDE's classes and nothing of the
     * plugin's.
     */
    val libraryLoader: ClassLoader? = null,

    /**
     * Which sandbox categories to refuse (see [SandboxCategories]).
     *
     * Null, the default, means "whatever the user set for this project's previews", so a plugin's preview is
     * held to the same rules as the built-in one instead of quietly getting more access to the device. An
     * empty set restricts nothing, which is a decision to make deliberately and to be able to justify to the
     * user whose device it is.
     */
    val sandbox: Set<String>? = null,

    /**
     * What a refused operation does. False (the default) yields `null` and records an [InterpretProblem], so
     * one blocked call costs that call; true fails the whole run with an [InterpretException], for the cases
     * where a partial result would mislead.
     */
    val strictSandbox: Boolean = false,

    /**
     * Whether to run a program that lowered imperfectly, skipping the statements that did not lower. True (the
     * default) matches the editor preview: one unsupported widget costs that widget. False refuses such a
     * function outright, with the reason.
     */
    val tolerateGaps: Boolean = true,

    /** Mediates every call out of interpreted code into real code. Null checks nothing; see [InterpretHooks]. */
    val hooks: InterpretHooks? = null,
)

/**
 * How a bytecode session behaves.
 *
 * There is no sandbox here, and that is a real difference from [InterpretConfig] rather than an omission. The
 * VM's boundary is its own bridge into real code, not the source interpreter's hook seam, so a category
 * sandbox is something a host installs in that bridge (as the console run does through its own guard) and not
 * something this session can apply per call. A bytecode session therefore runs the project's compiled code
 * with the IDE's own access, which is what a plugin declares by naming the `interp.run` capability. Where a
 * preview can work from source, prefer [InterpretConfig], which is sandboxed by default.
 *
 * The policy pair is the part worth thinking about. The VM interprets what [interpretPrefixes] claims and
 * bridges everything else to real code, and the boundary decides both correctness and speed: interpreting a
 * whole framework is slow and warms up slowly (a large jar costs a one-time parse of every class it reaches),
 * while bridging code the plugin does not actually have on hand fails at the call. The usual answer is to
 * interpret the user's own packages and bridge the rest.
 */
class BytecodeConfig(
    /** Directories and jars holding the classes to run, read as an ordinary JVM classpath. */
    val classpath: List<Path>,

    /**
     * Binary name prefixes (`com.example.`, or `com/example/`) to interpret. Empty interprets anything on
     * [classpath] that the default policy does not reserve for the platform, which is the console run's
     * behaviour.
     */
    val interpretPrefixes: List<String> = emptyList(),

    /**
     * Binary name prefixes to bridge to real code even when [interpretPrefixes] would claim them. Use it to
     * carve the framework out of a package the plugin otherwise interprets.
     */
    val bridgePrefixes: List<String> = emptyList(),

    /** The loader bridged classes resolve against; see [InterpretConfig.libraryLoader]. */
    val libraryLoader: ClassLoader? = null,

    /**
     * Stack size in bytes for a thread the interpreted program starts, 0 for the host default. Interpreted
     * recursion runs on the real thread stack, so it overflows far shallower than compiled code; a program
     * that spawns worker threads and recurses on them wants this set (the console run uses 16 MB).
     */
    val threadStackSize: Long = 0,
)

/**
 * What a sandbox can refuse. The ids match the IDE's own preview sandbox settings, so a plugin naming them
 * lands on the same categories the user already configured under Compose Preview.
 */
object SandboxCategories {

    /** Reading and writing files. */
    const val FILE_IO = "fileIo"

    /** Network access. */
    const val NETWORK = "network"

    /** Android system services: the package manager, telephony, settings, and the rest of `Context`. */
    const val ANDROID_SYSTEM = "androidSystem"

    /** Starting processes, and reflection used to reach past the other categories. */
    const val PROCESS_CONTROL = "processControl"

    /** Every category, i.e. the most restrictive sandbox available. */
    val ALL: Set<String> = setOf(FILE_IO, NETWORK, ANDROID_SYSTEM, PROCESS_CONTROL)
}
