package com.zipcli.ke.domain

/**
 * Stable error codes and exit statuses.
 *
 * Every error line is `[E_CODE] human sentence`, so a caller can branch on the code without parsing prose.
 * The exit-status table is published in docs/SCRIPTING.md and in `help`:
 *
 *   0 success | 1 no match / false predicate / other failure | 2 usage error | 3 path not found
 *   4 needs confirmation | 5 partial batch (some commands did not run)
 */
object ErrorCodes {
    const val USAGE = "E_USAGE"
    const val NOT_FOUND = "E_NOT_FOUND"
    const val IS_DIR = "E_IS_DIR"
    const val NEEDS_CONFIRM = "E_NEEDS_CONFIRM"
    const val INTERNAL = "E_INTERNAL"
    /** Anything else that went wrong in a command (already exists, permission, I/O ...). */
    const val FAILED = "E_FAILED"

    const val STATUS_OK = 0
    const val STATUS_FAILED = 1
    const val STATUS_USAGE = 2
    const val STATUS_NOT_FOUND = 3
    const val STATUS_NEEDS_CONFIRM = 4
    const val STATUS_PARTIAL = 5

    val ALL = listOf(USAGE, NOT_FOUND, IS_DIR, NEEDS_CONFIRM, INTERNAL, FAILED)

    fun statusFor(code: String): Int = when (code) {
        USAGE -> STATUS_USAGE
        NOT_FOUND -> STATUS_NOT_FOUND
        NEEDS_CONFIRM -> STATUS_NEEDS_CONFIRM
        else -> STATUS_FAILED
    }

    /** Picks the code for a message that did not name one itself. */
    fun classify(message: String): String = when {
        message.startsWith("Internal error") -> INTERNAL
        message.startsWith("Not found") || message.startsWith("No such") || message.startsWith("No files match") -> NOT_FOUND
        message.contains("is a directory") -> IS_DIR
        message.startsWith("Unknown command") || message.startsWith("Unknown flag") || message.startsWith("Usage") ||
            message.startsWith("Syntax error") || message.contains("requires a value") ||
            message.contains("does not take a value") || message.contains("takes no value") ||
            message.contains("cannot be combined") || message.contains("not supported") ||
            message.contains("looks like a shell prompt") -> USAGE
        else -> FAILED
    }

    fun tag(code: String) = "[$code]"
}
