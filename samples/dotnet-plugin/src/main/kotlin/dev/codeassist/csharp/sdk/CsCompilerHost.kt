package dev.codeassist.csharp.sdk

import dev.codeassist.csharp.util.ToolCancellation

/**
 * The resident compiler: `csc` kept loaded in the server ([CsServer]), so a compile pays for compiling and
 * not for starting the runtime and Roslyn again. Takes the arguments `csc` would and gives back its output
 * and exit code.
 */
internal object CsCompilerHost {

    /** Switch for the resident compiler; false sends every compile to a plain `csc`. */
    const val ENABLED = true

    /** Compiles with [arguments]. Returns `csc`'s exit code, or null if the server could not be used. */
    fun compile(
        sdk: DotnetSdk,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? = CsServer.compile(sdk, listOf("@@compile") + arguments, cancellation, onLine)
}
