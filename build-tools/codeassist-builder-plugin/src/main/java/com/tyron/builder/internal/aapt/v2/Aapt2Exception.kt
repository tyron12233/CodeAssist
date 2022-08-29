package com.tyron.builder.internal.aapt.v2

import com.android.utils.ILogger

/** Exception thrown when an error occurs during `aapt2` processing.  */
class Aapt2Exception private constructor(
    val description: String,
    cause: Throwable? = null,
    val output: String? = null,
    val processName: String? = null,
    val command: String? = null
 ) : RuntimeException(makeMessage(description, output), cause) {
    companion object {
        private const val serialVersionUID = 7034893190645766936L
        @JvmStatic
        @JvmOverloads
        fun create(
            logger: ILogger? = null,
            description: String,
            cause: Throwable? = null,
            output: String? = null,
            processName: String? = null,
            command: String? = null

        ) : Aapt2Exception {
            logger?.info("$description:\n" +
                    "process = $processName:\n" +
                    "command = $command")
            return Aapt2Exception(
                description = description,
                cause = cause,
                output = output,
                processName = processName,
                command = command)
        }
    }
}
private fun makeMessage(description: String, output: String?):String {
    if (output == null) {
        return description
    }
    return description + "\n" + output
}
